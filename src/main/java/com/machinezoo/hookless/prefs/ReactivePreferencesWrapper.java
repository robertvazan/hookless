// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.prefs;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.throwing.*;
import com.machinezoo.stagean.*;

/*
 * This is a wrapper around Preferences, but we in fact assume behavior of AbstractPreferences as currently implemented.
 * Most importantly, we assume that events are executed asynchronously on separate thread as is done in AbstractPreferences.
 * This implementation will still work with inline invocation of listeners as long as no lock is held by Preferences during invocation.
 */
@NoTests
class ReactivePreferencesWrapper extends AbstractReactivePreferences {
	/*
	 * Read-only methods operating on the node tree are easy to define. Let's get that done first.
	 */
	@Override
	public ReactivePreferencesWrapper parent() {
		return (ReactivePreferencesWrapper)super.parent();
	}
	private ReactivePreferencesWrapper root() {
		ReactivePreferencesWrapper root = this;
		while (root.parent() != null)
			root = root.parent();
		return root;
	}
	@Override
	public boolean isUserNode() {
		/*
		 * Root always has a non-null Preferences link.
		 */
		return root().link.isUserNode();
	}
	/*
	 * Utility method to capture result of synchronous invocations on Preferences.
	 */
	private static CompletableFuture<Void> complete(ThrowingRunnable runnable) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		try {
			runnable.run();
			future.complete(null);
		} catch (Throwable ex) {
			future.completeExceptionally(ex);
		}
		return future;
	}
	/*
	 * We want per-key reactivity, which means one ReactiveVariable for every accessed key.
	 * We could store actual values in them, but that adds state redundancy
	 * and more importantly we sometimes want to invalidate the variables
	 * even when we don't know whether they have actually changed.
	 * Even non-existent (null) keys will have ReactiveVariable if they are ever accessed.
	 * 
	 * In the future, we might want to offer an API that provides node-level reactivity only.
	 */
	private final Map<String, ReactiveVariable<Object>> kversions = new HashMap<>();
	/*
	 * We will have one per-node ReactiveVariable to cover key and child enumeration.
	 * We could split this up, but the additional granularity brings little benefit.
	 */
	private final ReactiveVariable<Object> version = OwnerTrace
		.of(new ReactiveVariable<Object>())
		.parent(this)
		.tag("role", "version")
		.target();
	private void invalidateKey(String key) {
		/*
		 * Remove the variable instead of just changing it.
		 * It might be faster, but most importantly, it's safe in case lots of keys are added and then removed.
		 */
		ReactiveVariable<Object> kversion = kversions.remove(key);
		if (kversion != null)
			kversion.set(new Object());
	}
	private void invalidateNode() {
		for (String key : kversions.keySet())
			invalidateKey(key);
		version.set(new Object());
	}
	/*
	 * A strong reference to the associated Preferences node. When null, this node is virtual.
	 * Virtual node may still exist in the backing store. We never check, because that information is immediately stale.
	 * We just materialize the node on first use in any read or write operation.
	 * 
	 * It doesn't need to be reactive, because no output depends on it as this node is materialized before use.
	 */
	private Preferences link;
	private void attach(Preferences prefs) {
		/*
		 * There should be no previous Preferences instance, but let's make sure we clean it up just in case there was one.
		 */
		if (link != null)
			detach(link);
		link = prefs;
		/*
		 * This adds a strong reference from Preferences to ReactivePreferences. The two objects always reference each other.
		 * Unsubscription is done only when link between detaching removed Preferences.
		 */
		prefs.addPreferenceChangeListener(keyListener);
		prefs.addNodeChangeListener(nodeListener);
	}
	private void detach(Preferences prefs) {
		if (prefs != null) {
			/*
			 * This could be called after a delay on node with evolved state,
			 * so only clear the Preferences link if it has not changed meantime.
			 */
			if (link == prefs)
				link = null;
			prefs.removePreferenceChangeListener(keyListener);
			prefs.removeNodeChangeListener(nodeListener);
			/*
			 * When the node is detached, all previous reads become stale.
			 */
			invalidateNode();
		}
	}
	/*
	 * We will keep track of children, so that node listener can invoke detach() on removed child.
	 * AbstractReactivePreferences already maintains such a child map, but there's no nice API for it.
	 */
	private final Map<String, WeakReference<ReactivePreferencesWrapper>> childRefs = new HashMap<>();
	/*
	 * Here we assume that Preferences runs listeners asynchronously on separate thread.
	 * This is not guaranteed by Preferences javadoc, but it's how AbstractPreferences are actually implemented.
	 * This code will still work if listeners run on the same thread after all locks have been released.
	 * Given these assumptions, we can afford locking in event handlers.
	 * 
	 * Store listener references in fields, so that we can unsubscribe them. Lambdas create new listener instance every time.
	 */
	private final PreferenceChangeListener keyListener = event -> {
		synchronized (lock) {
			invalidateKey(event.getKey());
			version.set(new Object());
		}
	};
	private final NodeChangeListener nodeListener = new NodeChangeListener() {
		@Override
		public void childAdded(NodeChangeEvent event) {
			/*
			 * No synchronization needed when just invalidating ReactiveVariable.
			 */
			version.set(new Object());
		}
		@Override
		public void childRemoved(NodeChangeEvent event) {
			synchronized (lock) {
				/*
				 * Detach the node to force construction of new Preferences when the node is materialized again.
				 * 
				 * Since child removal event arrives asynchronously on separate thread,
				 * there is a short time during which Preferences is in removed state while ReactivePreferences still points to it.
				 * This can cause exceptions when read/write methods are executed on ReactivePreferences.
				 * There is no way to avoid that unless we catch IllegalStateException everywhere,
				 * which we lazy to do, because the combination of non-reactive removeNode() with reactive access is unlikely.
				 * Calling removeNode() on ReactivePreferences is however safe as it detaches Preferences immediately.
				 */
				WeakReference<ReactivePreferencesWrapper> weak = childRefs.get(event.getChild().name());
				ReactivePreferencesWrapper child = weak != null ? weak.get() : null;
				if (child != null)
					child.detach(event.getChild());
				version.set(new Object());
			}
		}
	};
	/*
	 * In all reads and writes, materialization is preferred over checking whether the node exists.
	 * It is also consistent with how Preferences work.
	 */
	private Preferences materialize() {
		if (link == null)
			attach(parent().materialize().node(name()));
		return link;
	}
	private ReactivePreferencesWrapper(Preferences prefs, ReactivePreferencesWrapper parent, String name) {
		super(parent, name);
		if (prefs != null)
			attach(prefs);
	}
	ReactivePreferencesWrapper(Preferences prefs) {
		this(Objects.requireNonNull(prefs), null, "");
	}
	private ReactivePreferencesWrapper(ReactivePreferencesWrapper parent, String name) {
		this(null, parent, name);
	}
	@Override
	protected AbstractReactivePreferences childSpi(String name) {
		ReactivePreferencesWrapper child = new ReactivePreferencesWrapper(this, name);
		childRefs.put(name, new WeakReference<>(child));
		return child;
	}
	@Override
	protected String[] childrenNamesSpi() throws BackingStoreException {
		version.get();
		return materialize().childrenNames();
	}
	@Override
	protected CompletableFuture<Void> removeNodeSpi() {
		/*
		 * We have to materialize the node, because we don't know whether it exists in the backing store or not.
		 * We could check, but that's just an optimization that doesn't have impact worth the additional complexity.
		 */
		Preferences prefs = materialize();
		CompletableFuture<Void> future = complete(() -> prefs.removeNode());
		/*
		 * Detach immediately to make sure that calls to removeNode have immediate visible effect.
		 * We will also receive node change event, but that one will arrive asynchronously on separate thread.
		 */
		detach(prefs);
		return future;
	}
	@Override
	protected String[] keysSpi() throws BackingStoreException {
		version.get();
		return materialize().keys();
	}
	@Override
	protected String getSpi(String key) {
		Preferences prefs = materialize();
		ReactiveVariable<Object> kversion = kversions.get(key);
		if (kversion == null) {
			kversion = OwnerTrace
				.of(new ReactiveVariable<Object>())
				.parent(this)
				.tag("key", key)
				.target();
			kversions.put(key, kversion);
		}
		kversion.get();
		return prefs.get(key, null);
	}
	/*
	 * Write methods could rely on change notification for reactivity, but that would make all preferences asynchronous.
	 * To spare callers of having to deal with reads lagging behind writes, we will invalidate keys explicitly here.
	 * The downside is that now we will get two invalidations, one here and one in change listener.
	 * We cannot remove the change listeners either, because we want to receive invalidations for writes performed by non-reactive code.
	 */
	@Override
	protected void putSpi(String key, String value) {
		materialize().put(key, value);
		invalidateKey(key);
	}
	@Override
	protected void removeSpi(String key) {
		materialize().remove(key);
		invalidateKey(key);
	}
	@Override
	protected CompletableFuture<Void> flushSpi() {
		/*
		 * This is called only on root node, which is always linked to Preferences.
		 */
		return complete(() -> link.flush());
	}
}
