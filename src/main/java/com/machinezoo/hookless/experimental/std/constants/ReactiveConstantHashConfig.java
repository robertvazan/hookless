// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import java.io.*;
import java.util.*;
import com.machinezoo.hookless.experimental.*;

public class ReactiveConstantHashConfig implements ReactiveConstantConfig {
	private final ReactiveConstantHash key;
	public ReactiveConstantHashConfig(ReactiveConstantHash key) {
		Objects.requireNonNull(key);
		this.key = key;
	}
	@Override
	public ReactiveConstantHash key() {
		return key;
	}
	@Override
	public ReactiveVersion version() {
		return ReactiveVersionHash.hash(serialize(key.compute()));
	}
	public byte[] serialize(Serializable value) {
		return ReactiveKey.serialize(value);
	}
}
