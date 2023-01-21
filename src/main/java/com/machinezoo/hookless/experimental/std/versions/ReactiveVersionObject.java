// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.versions;

import java.io.*;
import java.util.*;
import com.machinezoo.hookless.experimental.*;

/*
 * Intended for small objects only. Large objects should be hashed. Object can be null.
 * Object must be serializable a stringifiable.
 */
public record ReactiveVersionObject<T extends Serializable>(T object) implements ReactiveVersion {
	@Override
	public ReactiveVersionHash toHash() {
		/*
		 * Hardcoded Java serialization. Change requires defining new version type and employing it everywhere.
		 */
		return ReactiveVersionHash.hash(object);
	}
	@Override
	public String toString() {
		return Objects.toString(object);
	}
}
