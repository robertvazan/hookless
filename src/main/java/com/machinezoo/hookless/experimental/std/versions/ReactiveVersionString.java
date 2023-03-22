// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import java.util.*;
import com.machinezoo.hookless.experimental.*;

/*
 * Intended for short strings only. Long strings should be hashed. String can be null.
 */
public record ReactiveVersionString(String text) implements ReactiveVersion {
	private static final ReactiveVersionHash PREFIX = ReactiveVersionHash.hash(ReactiveVersionString.class.getName());
	@Override
	public ReactiveVersionHash toHash() {
		return PREFIX.combine(text);
	}
	@Override
	public String toString() {
		return Objects.toString(text);
	}
}
