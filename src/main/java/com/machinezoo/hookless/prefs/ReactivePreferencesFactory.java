// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.prefs;

import java.util.prefs.*;
import com.machinezoo.stagean.*;

/**
 * Reactive version of {@link PreferencesFactory}.
 */
@StubDocs
public interface ReactivePreferencesFactory {
	ReactivePreferences systemRoot();
	ReactivePreferences userRoot();
}
