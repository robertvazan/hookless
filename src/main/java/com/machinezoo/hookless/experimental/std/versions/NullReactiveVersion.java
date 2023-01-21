// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import com.machinezoo.hookless.experimental.*;

/*
 * Version that never changes. Useful for reactive markers.
 */
public record NullReactiveVersion() implements ReactiveVersion {
	public static final NullReactiveVersion INSTANCE = new NullReactiveVersion();
	@Override
	public ReactiveVersionHash toHash() {
		return ReactiveVersionHash.ZERO;
	}
	@Override
	public String toString() {
		return "()";
	}
}
