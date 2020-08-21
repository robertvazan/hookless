// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.prefs;

import static java.util.stream.Collectors.*;
import java.lang.ref.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.prefs.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.*;
import com.machinezoo.noexception.throwing.*;
import com.machinezoo.stagean.*;

/*
 * There are some differences between AbstractPreferences and AbstractReactivePreferences:
 * - It reflects differences between Preferences and ReactivePreferences of course.
 * - The lock is tree-wide rather than per-node.
 */
/**
 * Reactive version of {@link AbstractPreferences}.
 */
@StubDocs
@NoTests
public abstract class AbstractReactivePreferences extends ReactivePreferences {
	/*
	 * First we define linking from children to parents.
	 */
	private final AbstractReactivePreferences parent;
	@Override
	public ReactivePreferences parent() {
		return parent;
	}
	private final String name;
	@Override
	public String name() {
		return name;
	}
	/*
	 * We will precompute some properties during object construction.
	 */
	private final String absolutePath;
	@Override
	public String absolutePath() {
		return absolutePath;
	}
	private final AbstractReactivePreferences root;
	/*
	 * There is only one lock for the whole tree. This is simpler, safer, and possibly faster than per-node lock.
	 * Explicit lock field matches AbstractPreferences API although that one has per-node lock.
	 */
	protected final Object lock;
	public AbstractReactivePreferences(AbstractReactivePreferences parent, String name) {
		this.parent = parent;
		this.name = name;
		absolutePath = parent == null ? "/" : parent.parent == null ? "/" + name : parent.absolutePath + "/" + name;
		root = parent == null ? this : parent.root;
		lock = parent == null ? new Object() : parent.lock;
		OwnerTrace.of(this)
			.alias("prefs")
			.tag("path", absolutePath);
	}
	/*
	 * This mimics behavior of AbstractPreferences. Most implementations might want to override this.
	 */
	@Override
	public boolean isUserNode() {
		return root == ReactivePreferences.userRoot();
	}
	/*
	 * Some methods receive node path. The methods below clean up and parse the path.
	 */
	private String resolve(String path) {
		if (path.contains("//"))
			throw new IllegalArgumentException("Path cannot contain empty path components.");
		if (path.endsWith("/") && !path.equals("/"))
			throw new IllegalArgumentException("Path cannot end in slash.");
		if (path.equals(""))
			path = absolutePath;
		else if (!path.startsWith("/"))
			path = (parent != null ? absolutePath + "/" : "/") + path;
		/*
		 * Check name component length.
		 */
		int start = 0;
		while (start < path.length() - 1) {
			int end = path.indexOf('/', start + 1);
			if (end < 0)
				end = path.length();
			if (end - start - 1 > Preferences.MAX_NAME_LENGTH)
				throw new IllegalArgumentException("Exceeded maximum node name length.");
			start = end;
		}
		return path;
	}
	private String[] components(String path) {
		path = resolve(path);
		/*
		 * Split would return a single-element array for empty input.
		 */
		if (path.equals("/"))
			return new String[0];
		return path.substring(1).split("/");
	}
	/*
	 * We will now define parent to children links. All constructed nodes can be enumerated.
	 * We allow deallocation of unreferenced nodes, so weakref all children.
	 * Due to parent links, node is only collected after all its descendants become unreachable.
	 * 
	 * Garbage collection of unreferenced subtrees is desirable, because it allows working with very large trees.
	 * Such large trees are convenient in some applications. Weakrefs are also consistent with other hookless APIs.
	 * Large trees usually grow large at one node that has a very large number of children.
	 * Child/key enumeration on that node will be still unacceptably slow.
	 * Checking for existence of its children will be slow as well.
	 */
	private final Map<String, WeakReference<AbstractReactivePreferences>> children = new HashMap<>();
	/*
	 * We can now proceed to create children. These are virtual children by default. They merely represent some path.
	 * No node is actually created, not even any ancestor node.
	 */
	protected abstract AbstractReactivePreferences childSpi(String name);
	private AbstractReactivePreferences child(String name) {
		WeakReference<AbstractReactivePreferences> weak = children.get(name);
		AbstractReactivePreferences child = weak != null ? weak.get() : null;
		if (child == null)
			children.put(name, new WeakReference<>(child = childSpi(name)));
		return child;
	}
	@Override
	public ReactivePreferences node(String path) {
		/*
		 * Always construct nodes starting from root, so that every node has a parent.
		 */
		AbstractReactivePreferences node = root;
		synchronized (lock) {
			for (String name : components(path)) {
				node = node.child(name);
			}
		}
		return node;
	}
	/*
	 * Once we have some children, we can enumerate them and check for their existence.
	 * This is a reactive read method that can reactively block. All higher level methods can thus reactively block too.
	 * This method may be called on non-existent node. It should just return an empty array in that case.
	 */
	protected abstract String[] childrenNamesSpi() throws BackingStoreException;
	@Override
	public String[] childrenNames() throws BackingStoreException {
		synchronized (lock) {
			return Arrays.stream(childrenNamesSpi()).sorted().toArray(String[]::new);
		}
	}
	@Override
	public boolean nodeExists(String path) throws BackingStoreException {
		path = resolve(path);
		String[] components = components(path);
		if (components.length == 0)
			return true;
		AbstractReactivePreferences parent = root;
		synchronized (lock) {
			for (int i = 0; i < components.length - 1; ++i) {
				parent = parent.child(components[i]);
			}
			return Arrays.stream(parent.childrenNamesSpi()).anyMatch(n -> n.equals(components[components.length - 1]));
		}
	}
	/*
	 * Sometimes, BackingStoreException is wrapped by CompletableFuture/ReactiveFuture.
	 * In those cases, it is useful bring it back to the front of exception chain to make it identifiable.
	 */
	private static void await(ReactiveFuture<?> future) throws BackingStoreException {
		try {
			future.get();
		} catch (CompletionException ex) {
			if (ex.getCause() instanceof BackingStoreException)
				throw new BackingStoreException(ex);
		}
	}
	/*
	 * Tree API is completed with node removal. This merely makes the node virtual. It can be resurrected later.
	 * Node removal is executed asynchronously. Its partial effects may be observed.
	 * Derived class is responsible for read and write consistency.
	 * 
	 * AbstractPreferences also locks node's parent during removal, but we opt to not do it.
	 * Derived class should use alternate means of synchronization if it needs locked parent.
	 */
	protected abstract CompletableFuture<Void> removeNodeSpi();
	private class RemoveNode implements ThrowingRunnable {
		ReactiveFuture<Void> nodeDone;
		List<ReactiveFuture<Void>> childrenDone;
		@Override
		public void run() throws BackingStoreException {
			if (childrenDone == null) {
				List<AbstractReactivePreferences> children;
				synchronized (lock) {
					/*
					 * If we fail to get child list for this node or any descendant, the whole removal recursively fails.
					 */
					String[] names = childrenNamesSpi();
					if (CurrentReactiveScope.blocked())
						return;
					children = Arrays.stream(names).map(n -> child(n)).collect(toList());
				}
				/*
				 * Here we execute child removal potentially concurrently.
				 */
				childrenDone = children.stream().map(c -> ReactiveFuture.wrap(c.removeNodeRecursively())).collect(toList());
			}
			/*
			 * This will throw in case any future is not completed, i.e. it's blocking.
			 */
			for (ReactiveFuture<Void> future : childrenDone)
				await(future);
			if (nodeDone == null) {
				synchronized (lock) {
					nodeDone = ReactiveFuture.wrap(removeNodeSpi());
				}
			}
			await(nodeDone);
		}
	}
	private CompletableFuture<Void> removeNodeRecursively() {
		return ReactiveFuture.runReactive(Exceptions.sneak().runnable(new RemoveNode()));
	}
	@Override
	public CompletableFuture<Void> removeNode() {
		if (parent == null)
			throw new UnsupportedOperationException();
		return removeNodeRecursively();
	}
	/*
	 * Now that we have the tree API covered, we can focus on keys. This is the easy part.
	 * When keys are accessed on removed (virtual) nodes, reads return fallback and writes create the node (and its ancestors).
	 */
	protected abstract String[] keysSpi() throws BackingStoreException;
	@Override
	public String[] keys() throws BackingStoreException {
		return Arrays.stream(keysSpi()).sorted().toArray(String[]::new);
	}
	protected abstract String getSpi(String key);
	@Override
	public String get(String key, String def) {
		/*
		 * Do not check key length in get methods.
		 */
		Objects.requireNonNull(key);
		synchronized (lock) {
			try {
				String value = getSpi(key);
				return value != null ? value : def;
			} catch (Throwable ex) {
				return def;
			}
		}
	}
	@Override
	public boolean getBoolean(String key, boolean def) {
		switch (get(key, "").toLowerCase()) {
		case "true":
			return true;
		case "false":
			return false;
		default:
			return def;
		}
	}
	@Override
	public byte[] getByteArray(String key, byte[] def) {
		try {
			String text = get(key, null);
			if (text != null)
				return Base64.getDecoder().decode(text);
		} catch (IllegalArgumentException ex) {
		}
		return def;
	}
	@Override
	public double getDouble(String key, double def) {
		try {
			String text = get(key, null);
			if (text != null)
				return Double.parseDouble(text);
		} catch (NumberFormatException ex) {
		}
		return def;
	}
	@Override
	public float getFloat(String key, float def) {
		try {
			String text = get(key, null);
			if (text != null)
				return Float.parseFloat(text);
		} catch (NumberFormatException ex) {
		}
		return def;
	}
	@Override
	public int getInt(String key, int def) {
		try {
			String text = get(key, null);
			if (text != null)
				return Integer.parseInt(text);
		} catch (NumberFormatException ex) {
		}
		return def;
	}
	@Override
	public long getLong(String key, long def) {
		try {
			String text = get(key, null);
			if (text != null)
				return Long.parseLong(text);
		} catch (NumberFormatException ex) {
		}
		return def;
	}
	protected abstract void putSpi(String key, String value);
	@Override
	public synchronized void put(String key, String value) {
		if (key.length() > Preferences.MAX_KEY_LENGTH)
			throw new IllegalArgumentException("Exceeded maximum key length.");
		if (value.length() > Preferences.MAX_VALUE_LENGTH)
			throw new IllegalArgumentException("Exceeded maximum value length.");
		synchronized (lock) {
			putSpi(key, value);
		}
	}
	@Override
	public void putBoolean(String key, boolean value) {
		put(key, Boolean.toString(value));
	}
	@Override
	public void putByteArray(String key, byte[] value) {
		Objects.requireNonNull(value);
		put(key, Base64.getEncoder().encodeToString(value));
	}
	@Override
	public void putDouble(String key, double value) {
		put(key, Double.toString(value));
	}
	@Override
	public void putFloat(String key, float value) {
		put(key, Float.toString(value));
	}
	@Override
	public void putInt(String key, int value) {
		put(key, Integer.toString(value));
	}
	@Override
	public void putLong(String key, long value) {
		put(key, Long.toString(value));
	}
	protected abstract void removeSpi(String key);
	@Override
	public void remove(String key) {
		/*
		 * Do not check key length when removing keys.
		 */
		Objects.requireNonNull(key);
		synchronized (lock) {
			removeSpi(key);
		}
	}
	@Override
	public CompletableFuture<Void> clear() {
		return ReactiveFuture.runReactive(() -> {
			String[] keys;
			synchronized (lock) {
				keys = Exceptions.sneak().get(() -> childrenNamesSpi());
			}
			if (CurrentReactiveScope.blocked())
				return;
			for (String key : keys)
				remove(key);
		});
	}
	protected abstract CompletableFuture<Void> flushSpi();
	@Override
	public CompletableFuture<Void> flush() {
		synchronized (lock) {
			return root.flushSpi();
		}
	}
}
