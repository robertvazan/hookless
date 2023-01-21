// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import java.util.*;
import com.machinezoo.hookless.experimental.*;
import com.machinezoo.hookless.experimental.std.versions.*;

public class ReactiveConstantStringConfig implements ReactiveConstantConfig {
	private final ReactiveConstantString key;
	public ReactiveConstantStringConfig(ReactiveConstantString key) {
		Objects.requireNonNull(key);
		this.key = key;
	}
	@Override
	public ReactiveConstantString key() {
		return key;
	}
	@Override
	public ReactiveVersion version() {
		return new ReactiveVersionString(key.compute());
	}
}
