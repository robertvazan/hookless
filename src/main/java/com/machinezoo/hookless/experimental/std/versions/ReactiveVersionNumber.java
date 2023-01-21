// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import com.machinezoo.hookless.experimental.*;

public record ReactiveVersionNumber(long number) implements ReactiveVersion {
	@Override
	public ReactiveVersionHash toHash() {
		return new ReactiveVersionHash(0, 0, 0, number);
	}
	@Override
	public String toString() {
		return Long.toString(number);
	}
}
