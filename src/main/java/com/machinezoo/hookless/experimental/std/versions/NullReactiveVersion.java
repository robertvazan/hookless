// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import com.machinezoo.hookless.experimental.*;

/*
 * Version that never changes. Useful for reactive markers.
 */
public record NullReactiveVersion() implements ReactiveVersion {
	public static final NullReactiveVersion INSTANCE = new NullReactiveVersion();
	private static final ReactiveVersionHash HASH = ReactiveVersionHash.hash(NullReactiveVersion.class.getName());
	@Override
	public ReactiveVersionHash toHash() {
		return HASH;
	}
	@Override
	public String toString() {
		return "()";
	}
}
