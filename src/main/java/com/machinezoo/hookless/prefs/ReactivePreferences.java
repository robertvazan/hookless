// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.prefs;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.prefs.*;
import com.google.common.base.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/*
 * We could inherit from Preferences, but we want to document the behavioral differences (reactive blocking),
 * declare ReactivePreferences in higher level APIs, and drop support for listeners
 * that would complicate custom implementations.
 * 
 * ReactivePreferences differ in behavior from Preferences in several ways:
 * - It is reactive, of course. All reads create reactive dependencies. There are no event listeners.
 * - Nodes are not created on access. They are created when first key is written or when first child node is created.
 * - It is therefore possible to hold references to non-existent virtual nodes. Node removal merely makes the node virtual.
 * - Writes that cannot be buffered return CompletableFuture to allow them to run asynchronously.
 * - If an operation returns CompletableFuture, it never directly throws BackingStoreException.
 * - There's no sync(), because it would imply that reads are not reactive.
 * - Export is currently not supported.
 * 
 * Asynchronous (client-server) implementations may have additional pecularities:
 * - Read operations return fallbacks while reactively blocking.
 * - Read and write consistency may be relaxed depending on capabilities of the backing store.
 */
/**
 * Reactive version of {@link Preferences}.
 */
@StubDocs
@NoTests
@DraftApi("configurable wrap(), perhaps require strong consistency, export")
public abstract class ReactivePreferences {
	public abstract String name();
	public abstract String absolutePath();
	public abstract boolean isUserNode();
	public abstract ReactivePreferences parent();
	public abstract ReactivePreferences node(String path);
	public abstract boolean nodeExists(String path) throws BackingStoreException;
	public abstract String[] childrenNames() throws BackingStoreException;
	public abstract CompletableFuture<Void> removeNode();
	public abstract String get(String key, String def);
	public abstract boolean getBoolean(String key, boolean def);
	public abstract byte[] getByteArray(String key, byte[] def);
	public abstract double getDouble(String key, double def);
	public abstract float getFloat(String key, float def);
	public abstract int getInt(String key, int def);
	public abstract long getLong(String key, long def);
	public abstract String[] keys() throws BackingStoreException;
	public abstract void put(String key, String value);
	public abstract void putBoolean(String key, boolean value);
	public abstract void putByteArray(String key, byte[] value);
	public abstract void putDouble(String key, double value);
	public abstract void putFloat(String key, float value);
	public abstract void putInt(String key, int value);
	public abstract void putLong(String key, long value);
	public abstract void remove(String key);
	public abstract CompletableFuture<Void> clear();
	public abstract CompletableFuture<Void> flush();
	@Override
	public String toString() {
		return (isUserNode() ? "User" : "System") + " preferences: " + absolutePath();
	}
	/*
	 * We will use WeakHashMap to ensure there is at most one ReactivePreferencesWrapper for every Preferences root.
	 * This is essential to avoid piling up change listeners in Preferences.
	 * 
	 * Preferences and ReactivePreferencesWrapper hold strong reference to each other. They are collected only when both of them are unreferenced.
	 * We just have to be careful not to add any new strong references in the global association table.
	 * WeakHashMap strongly references its values by default. We have to explicitly make values weak too.
	 */
	private static final WeakHashMap<Preferences, WeakReference<ReactivePreferencesWrapper>> roots = new WeakHashMap<>();
	/*
	 * We might want to provide an overload taking options object that specifies
	 * preferred granularity of reactivity (global, node, or key) and async execution of reads/writes.
	 * 
	 * We don't want corresponding unwrap() method. Reactive implementation should always sit above non-reactive ones.
	 */
	public static ReactivePreferences wrap(Preferences prefs) {
		if (!prefs.absolutePath().equals("/"))
			throw new IllegalStateException("Only root Preferences node can be wrapped.");
		WeakReference<ReactivePreferencesWrapper> weak = roots.get(prefs);
		ReactivePreferencesWrapper reactive = weak != null ? weak.get() : null;
		if (reactive == null) {
			reactive = new ReactivePreferencesWrapper(prefs);
			roots.put(prefs, new WeakReference<>(reactive));
		}
		return reactive;
	}
	private static ReactivePreferencesFactory configure() {
		String name = System.getProperty(ReactivePreferencesFactory.class.getName());
		if (name != null)
			return (ReactivePreferencesFactory)Exceptions
				.wrap(ex -> new TypeNotPresentException(name, ex))
				.get(() -> Class.forName(name).getDeclaredConstructor().newInstance());
		for (ReactivePreferencesFactory factory : ServiceLoader.load(ReactivePreferencesFactory.class))
			return factory;
		return ReactivePreferencesFactoryWrapper.instance;
	}
	private static final Supplier<ReactivePreferencesFactory> factory = Suppliers.memoize(() -> configure());
	public static ReactivePreferences systemRoot() {
		return factory.get().systemRoot();
	}
	public static ReactivePreferences userRoot() {
		return factory.get().userRoot();
	}
	private static String packagePath(Class<?> clazz) {
		if (clazz.isArray())
			throw new IllegalArgumentException("No preferences node for arrays.");
		String name = clazz.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0)
            return "<unnamed>";
        return name.substring(0, dot).replace('.', '/');
	}
	public static ReactivePreferences systemNodeForPackage(Class<?> clazz) {
		return systemRoot().node(ReactivePreferences.packagePath(clazz));
	}
	public static ReactivePreferences userNodeForPackage(Class<?> clazz) {
		return userRoot().node(ReactivePreferences.packagePath(clazz));
	}
}
