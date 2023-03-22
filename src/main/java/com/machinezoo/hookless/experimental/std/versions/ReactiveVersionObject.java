// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import java.util.*;
import com.machinezoo.hookless.experimental.*;

/*
 * Intended for small objects only. Large objects should be hashed. Object can be null.
 * Object must have the same properties as ReactiveKey plus it must satisfy requirements of ReactiveVersionHash.hash(object).
 */
public record ReactiveVersionObject(Object object) implements ReactiveVersion {
	private static final ReactiveVersionHash PREFIX = ReactiveVersionHash.hash(ReactiveVersionObject.class.getName());
	@Override
	public ReactiveVersionHash toHash() {
		return PREFIX.combine(object);
	}
	@Override
	public String toString() {
		return Objects.toString(object);
	}
}
