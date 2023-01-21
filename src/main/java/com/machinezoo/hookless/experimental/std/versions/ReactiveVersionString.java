// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import java.util.*;
import com.machinezoo.hookless.experimental.*;

/*
 * Intended for short strings only. Long strings should be hashed. String can be null.
 */
public record ReactiveVersionString(String text) implements ReactiveVersion {
	@Override
	public ReactiveVersionHash toHash() {
		return ReactiveVersionHash.hash(text);
	}
	@Override
	public String toString() {
		return Objects.toString(text);
	}
}
