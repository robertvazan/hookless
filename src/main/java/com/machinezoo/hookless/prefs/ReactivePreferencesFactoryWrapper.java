// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.prefs;

import java.util.prefs.*;
import com.machinezoo.stagean.*;

@NoTests
class ReactivePreferencesFactoryWrapper implements ReactivePreferencesFactory {
	private final ReactivePreferences systemRoot;
	private final ReactivePreferences userRoot;
	@Override
	public ReactivePreferences systemRoot() {
		return systemRoot;
	}
	@Override
	public ReactivePreferences userRoot() {
		return userRoot;
	}
	ReactivePreferencesFactoryWrapper(PreferencesFactory inner) {
		systemRoot = ReactivePreferences.wrap(inner.systemRoot());
		userRoot = ReactivePreferences.wrap(inner.userRoot());
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
