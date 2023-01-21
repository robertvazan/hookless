// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

public interface ReactiveVersion extends ReactiveKey {
	/*
	 * All version objects are convertible to a hash.
	 */
	ReactiveVersionHash toHash();
}
