// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import java.util.*;
import com.machinezoo.hookless.experimental.*;
import com.machinezoo.hookless.experimental.std.versions.*;

public class ReactiveConstantObjectConfig<T> implements ReactiveConstantConfig {
	private final ReactiveConstantObject<T> key;
	public ReactiveConstantObjectConfig(ReactiveConstantObject<T> key) {
		Objects.requireNonNull(key);
		this.key = key;
	}
	@Override
	public ReactiveConstantObject<T> key() {
		return key;
	}
	@Override
	public ReactiveVersion version() {
		return new ReactiveVersionObject(key.compute());
	}
}
