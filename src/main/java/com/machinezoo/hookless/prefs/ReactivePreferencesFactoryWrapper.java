// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.prefs;

import java.util.function.Supplier;
import java.util.prefs.*;
import com.google.common.base.*;
import com.machinezoo.stagean.*;

@NoTests
class ReactivePreferencesFactoryWrapper implements ReactivePreferencesFactory {
	/*
	 * Make the wrappers lazy, so that we don't initialize systemRoot unnecessarily.
	 * Initializing systemRoot triggers warnings every 30s when JRE tries to sync it
	 * and discovers it cannot access its location under /etc, at least on linux.
	 */
	private final Supplier<ReactivePreferences> systemRoot;
	private final Supplier<ReactivePreferences> userRoot;
	@Override
	public ReactivePreferences systemRoot() {
		return systemRoot.get();
	}
	@Override
	public ReactivePreferences userRoot() {
		return userRoot.get();
	}
	ReactivePreferencesFactoryWrapper(PreferencesFactory inner) {
		systemRoot = Suppliers.memoize(() -> ReactivePreferences.wrap(inner.systemRoot()));
		userRoot = Suppliers.memoize(() -> ReactivePreferences.wrap(inner.userRoot()));
	}
	static final ReactivePreferencesFactory instance = new ReactivePreferencesFactoryWrapper(new PreferencesFactory() {
		@Override
		public Preferences systemRoot() {
			return Preferences.systemRoot();
		}
		@Override
		public Preferences userRoot() {
			return Preferences.userRoot();
		}
	});
}
