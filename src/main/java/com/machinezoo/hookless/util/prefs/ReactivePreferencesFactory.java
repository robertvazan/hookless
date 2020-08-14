// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.util.prefs;

import java.lang.ref.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.prefs.*;
import com.google.common.base.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/**
 * Reactive wrapper for {@link PreferencesFactory}.
 */
@StubDocs
@NoTests
@DraftApi("configurable granularity for reactivity, wrapAsync")
public class ReactivePreferencesFactory implements PreferencesFactory {
	/*
	 * We will use WeakHashMap to ensure there is at most one ReactivePreferences instance for every Preferences instance.
	 * This is essential to avoid piling up change listeners in Preferences and to ensure correct behavior of ReactivePreferences.
	 * If the underlying Preferences implementation allows multiple instances of Preferences for the same path,
	 * we will be forced to separately wrap each one of them, because Preferences API does not provide us with reliable global identity.
	 * Our own child access API is nevertheless structured to encourage single instance per path.
	 * 
	 * Preferences and ReactivePreferences hold strong reference to each other. They are collected only when both of them are unreferenced.
	 * We just have to be careful not to add any new strong references in the global association table.
	 * WeakHashMap strongly references its values by default. We have to explicitly make values weak too.
	 */
	private static final WeakHashMap<Preferences, WeakReference<ReactivePreferences>> roots = new WeakHashMap<>();
	/*
	 * We might later need wrapAsync that can deal with slow reads and writes.
	 * That one would have to return fallback instead of blocking when executing given read for the first time.
	 * It would also have to implement some sort of caching to ensure we don't reactively block repeatedly on the same key.
	 * Returning fallback values while fetch is in progress is however compliant with Preferences API,
	 * because it can be explained as temporary unavailability of the backing store.
	 * 
	 * We might also want unwrap() that takes reactive Preferences and returns blocking Preferences.
	 * It could use ReactiveFuture.supplyReactive() in getters.
	 */
	public static synchronized Preferences wrap(Preferences prefs) {
		if (!Exceptions.sneak().getAsBoolean(() -> prefs.nodeExists("")))
			throw new IllegalStateException("Node has been removed.");
		String path = prefs.absolutePath();
		Preferences root = path.equals("/") ? prefs : prefs.node("/");
		WeakReference<ReactivePreferences> weak = roots.get(root);
		ReactivePreferences reactive = weak != null ? weak.get() : null;
		if (reactive == null) {
			reactive = new ReactivePreferences(root, null);
			roots.put(prefs, new WeakReference<>(reactive));
		}
		return reactive.node(path);
	}
	/*
	 * We can now wrap roots of any PreferencesFactory.
	 * We will cache them just in case the underlying implementation does not enforce single root instance.
	 */
	private final Supplier<Preferences> systemRoot;
	public Preferences systemRoot() {
		return systemRoot.get();
	}
	private final Supplier<Preferences> userRoot;
	public Preferences userRoot() {
		return userRoot.get();
	}
	public ReactivePreferencesFactory(PreferencesFactory inner) {
		systemRoot = Suppliers.memoize(() -> wrap(inner.systemRoot()));
		userRoot = Suppliers.memoize(() -> wrap(inner.userRoot()));
	}
	/*
	 * Non-reactive versions of these methods are defined on Preferences.
	 * Since we don't expose ReactivePreferences and we need an underlying Preferences implementation,
	 * we implement the package node methods here.
	 */
	private static String packagePath(Class<?> clazz) {
		if (clazz.isArray())
			throw new IllegalArgumentException("No preferences node for arrays.");
		String name = clazz.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0)
            return "<unnamed>";
        return name.substring(0, dot).replace('.', '/');
	}
	public Preferences systemNodeForPackage(Class<?> clazz) {
		return systemRoot().node(packagePath(clazz));
	}
	public Preferences userNodeForPackage(Class<?> clazz) {
		return userRoot().node(packagePath(clazz));
	}
	/*
	 * We need convenient API to wrap the default Preferences implementation.
	 */
	private static final Supplier<ReactivePreferencesFactory> common = Suppliers.memoize(() -> new ReactivePreferencesFactory(new PreferencesFactory() {
		@Override
		public Preferences systemRoot() {
			return Preferences.systemRoot();
		}
		@Override
		public Preferences userRoot() {
			return Preferences.userRoot();
		}
	}));
	public static ReactivePreferencesFactory common() {
		return common.get();
	}
}
