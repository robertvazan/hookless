// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import com.google.common.primitives.*;
import com.machinezoo.hookless.experimental.*;

public record ReactiveVersionNumber(long number) implements ReactiveVersion {
	private static final ReactiveVersionHash PREFIX = ReactiveVersionHash.hash(ReactiveVersionNumber.class.getName());
	@Override
	public ReactiveVersionHash toHash() {
		return PREFIX.combine(Longs.toByteArray(number));
	}
	@Override
	public String toString() {
		return Long.toString(number);
	}
}
