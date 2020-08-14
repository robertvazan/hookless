// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.util.prefs;

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/*
 * We could cut corners and base this on AbstractPreferences, but that would force materialization of the whole tree in memory.
 * We want to support unloadable nodes that are internally weakly referenced. We need our own implementation for that.
 */
@NoTests
class ReactivePreferences extends Preferences {
	private final Preferences prefs;
	/*
	 * Forwarded node properties.
	 */
	@Override
	public String name() {
		return prefs.name();
	}
	@Override
	public String absolutePath() {
		return prefs.absolutePath();
	}
	@Override
	public boolean isUserNode() {
		return prefs.isUserNode();
	}
	@Override
	public String toString() {
		return prefs.toString();
	}
	private void validateSelf() {
		if (!Exceptions.sneak().getAsBoolean(() -> prefs.nodeExists("")))
			throw new IllegalStateException("Node has been removed.");
	}
	/*
	 * We want per-key reactivity, which means one ReactiveVariable for every accessed key.
	 * We can just as well have those ReactiveVariable instances hold the last key value.
	 * This will deduplicate redundant change notifications and simplify access code.
	 * Even non-existent (null) keys will have ReactiveVariable if they are ever accessed.
	 * 
	 * In the future, we might want to offer an API that provides node-level reactivity only.
	 * 
	 * This is a ConcurrentMap, so that event listener can run unsynchronized and avoid deadlock risk.
	 */
	private final ConcurrentMap<String, ReactiveVariable<String>> values = new ConcurrentHashMap<>();
	/*
	 * We need one more variable for reactive key enumeration.
	 */
	private final ReactiveVariable<Object> valuesVersion = OwnerTrace
		.of(new ReactiveVariable<Object>())
		.parent(this)
		.tag("role", "values")
		.target();
	/*
	 * Children are not associated with any value, so there's no point in giving them individual reactivity.
	 * There's nodeExists(), but it's rarely used and nobody will mind coarse-grained invalidation for it.
	 * We need only one ReactiveVariable to make child enumeration reactive.
	 */
	private final ReactiveVariable<Object> childrenVersion = OwnerTrace
		.of(new ReactiveVariable<Object>())
		.parent(this)
		.tag("role", "children")
		.target();
	/*
	 * Preferences API allows both fully materialized implementations that keep all Preferences instance in memory
	 * and on-demand implementations that create Preferences instances on access and deallocate them when not in use.
	 * On-demand implementations might create multiple Preferences instances for the same absolute path.
	 * Duplicate Preferences instances would add change notification overhead and they increase the risk of bugs and consistency issues.
	 * 
	 * We will structure our API so that it does not cause instance duplication during normal use.
	 * We will do this by constructing parallel node tree with strong parent and weak child references.
	 * The parallel mirror tree is partial, not all nodes reachable from the root are part of it,
	 * but if a node is included, all of its ancestors are included as well.
	 */
	private final ReactivePreferences parent;
	@Override
	public ReactivePreferences parent() {
		validateSelf();
		return parent;
	}
	/*
	 * This is a ConcurrentMap, so that event listener can run unsynchronized and avoid deadlock risk.
	 */
	private final ConcurrentMap<String, WeakReference<ReactivePreferences>> children = new ConcurrentHashMap<>();
	/*
	 * Event listeners are unsynchronized to avoid deadlock risk as the underlying Preferences instance is likely synchronized too.
	 * Since change callbacks have inverted order of objects on the stack, they acquire locks in opposite order too.
	 * If forward call chain (read/write) is combined with backward call chain (change notification), deadlock could occur.
	 */
	private void notifyKey(PreferenceChangeEvent event) {
		ReactiveVariable<String> value = values.get(event.getKey());
		/*
		 * If the key was never read, we don't have to do any notification.
		 */
		if (value != null)
			value.set(event.getNewValue());
		valuesVersion.set(new Object());
	}
	private void invalidateAllKeys() {
		for (String key : values.keySet()) {
			ReactiveVariable<String> value = values.remove(key);
			if (value != null)
				value.set(null);
		}
		valuesVersion.set(new Object());
	}
	private class ChildListener implements NodeChangeListener {
		@Override
		public void childAdded(NodeChangeEvent event) {
			childrenVersion.set(new Object());
		}
		@Override
		public void childRemoved(NodeChangeEvent event) {
			/*
			 * Remove the node from our parallel tree to force construction of new node if it is accessed again.
			 */
			WeakReference<ReactivePreferences> weak = children.remove(event.getChild().name());
			ReactivePreferences child = weak != null ? weak.get() : null;
			if (child != null) {
				/*
				 * Change notification for affected keys is not generated when node is removed per Preferences javadoc.
				 * We however want all computations that read the keys to be get invalidated,
				 * because the effective values of these keys have changed.
				 */
				invalidateAllKeys();
			}
			childrenVersion.set(new Object());
		}
	}
	ReactivePreferences(Preferences prefs, ReactivePreferences parent) {
		this.prefs = prefs;
		this.parent = parent;
		OwnerTrace.of(this)
			.alias("prefs")
			.tag("path", prefs.absolutePath());
		/*
		 * This adds a strong reference from Preferences to ReactivePreferences. The two objects always reference each other.
		 * The event listeners are never removed. They remain registered until garbage collectors kills both objects.
		 */
		prefs.addPreferenceChangeListener(this::notifyKey);
		prefs.addNodeChangeListener(new ChildListener());
	}
	private String resolve(String path) {
		if (path.contains("//"))
			throw new IllegalArgumentException("Path cannot contain empty path components.");
		if (path.endsWith("/") && !path.equals("/"))
			throw new IllegalArgumentException("Path cannot end in slash.");
		if (path.equals(""))
			return prefs.absolutePath();
		if (!path.startsWith("/"))
			return prefs.absolutePath() + "/" + path;
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
	private synchronized ReactivePreferences child(String name) {
		WeakReference<ReactivePreferences> weak = children.get(name);
		ReactivePreferences child = weak != null ? weak.get() : null;
		if (child == null)
			children.put(name, new WeakReference<>(child = new ReactivePreferences(prefs.node(name), this)));
		return child;
	}
	private ReactivePreferences root() {
		ReactivePreferences root = this;
		/*
		 * Use member variables directly. We want this to work even for removed nodes.
		 */
		while (root.parent != null)
			root = root.parent;
		return root;
	}
	@Override
	public ReactivePreferences node(String path) {
		validateSelf();
		/*
		 * Always construct nodes starting from root, so that every node has a parent in our parallel node tree.
		 */
		ReactivePreferences node = root();
		for (String name : components(path))
			node = node.child(name);
		return node;
	}
	@Override
	public boolean nodeExists(String path) throws BackingStoreException {
		/*
		 * We could support queries for other paths as well, but we want to match behavior of Preferences.
		 */
		if (!path.isEmpty())
			validateSelf();
		/*
		 * This method may be called on removed node, we have to examine all nodes starting with root.
		 */
		ReactivePreferences node = root();
		for (String name : components(path)) {
			/*
			 * Add dependency on all ancestor nodes.
			 */
			node.childrenVersion.get();
			if (!node.prefs.nodeExists(name))
				return false;
			node = node.child(name);
		}
		return true;
	}
	@Override
	public String[] childrenNames() throws BackingStoreException {
		childrenVersion.get();
		return prefs.childrenNames();
	}
	@Override
	public void removeNode() throws BackingStoreException {
		/*
		 * Rely on change notification for reactivity.
		 */
		prefs.removeNode();
	}
	@Override
	public synchronized String get(String key, String def) {
		validateSelf();
		Objects.requireNonNull(key);
		ReactiveVariable<String> value = values.get(key);
		if (value == null) {
			value = OwnerTrace
				.of(new ReactiveVariable<>(prefs.get(key, null)))
				.parent(this)
				.tag("key", key)
				.target();
			values.put(key, value);
		}
		return Optional.ofNullable(value.get()).orElse(def);
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
	@Override
	public String[] keys() throws BackingStoreException {
		valuesVersion.get();
		return prefs.keys();
	}
	/*
	 * Write methods rely on change notification for reactivity.
	 */
	@Override
	public void put(String key, String value) {
		prefs.put(key, value);
	}
	@Override
	public void putBoolean(String key, boolean value) {
		prefs.putBoolean(key, value);
	}
	@Override
	public void putByteArray(String key, byte[] value) {
		prefs.putByteArray(key, value);
	}
	@Override
	public void putDouble(String key, double value) {
		prefs.putDouble(key, value);
	}
	@Override
	public void putFloat(String key, float value) {
		prefs.putFloat(key, value);
	}
	@Override
	public void putInt(String key, int value) {
		prefs.putInt(key, value);
	}
	@Override
	public void putLong(String key, long value) {
		prefs.putLong(key, value);
	}
	@Override
	public void remove(String key) {
		prefs.remove(key);
	}
	@Override
	public void clear() throws BackingStoreException {
		/*
		 * This will generate change events for all removed keys per Preferences javadoc.
		 */
		prefs.clear();
	}
	@Override
	public void flush() throws BackingStoreException {
		prefs.flush();
	}
	private void invalidateTree() {
		invalidateAllKeys();
		for (WeakReference<ReactivePreferences> weak : children.values()) {
			ReactivePreferences child = weak.get();
			if (child != null)
				child.invalidateTree();
		}
	}
	@Override
	public void sync() throws BackingStoreException {
		prefs.sync();
		invalidateTree();
	}
	/*
	 * Just to support Preferences API.
	 */
	@Override
	public void addPreferenceChangeListener(PreferenceChangeListener pcl) {
		prefs.addPreferenceChangeListener(pcl);
	}
	@Override
	public void removePreferenceChangeListener(PreferenceChangeListener pcl) {
		prefs.removePreferenceChangeListener(pcl);
	}
	@Override
	public void addNodeChangeListener(NodeChangeListener ncl) {
		prefs.addNodeChangeListener(ncl);
	}
	@Override
	public void removeNodeChangeListener(NodeChangeListener ncl) {
		prefs.removeNodeChangeListener(ncl);
	}
	/*
	 * Non-reactive, because reactivity is rarely needed here.
	 */
	@Override
	public void exportNode(OutputStream os) throws IOException, BackingStoreException {
		prefs.exportNode(os);
	}
	@Override
	public void exportSubtree(OutputStream os) throws IOException, BackingStoreException {
		prefs.exportSubtree(os);
	}
}
