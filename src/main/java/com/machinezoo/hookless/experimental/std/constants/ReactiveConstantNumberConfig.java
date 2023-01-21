// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import java.util.*;
import com.machinezoo.hookless.experimental.*;
import com.machinezoo.hookless.experimental.std.versions.*;

public class ReactiveConstantNumberConfig implements ReactiveConstantConfig {
	private final ReactiveConstantNumber key;
	public ReactiveConstantNumberConfig(ReactiveConstantNumber key) {
		Objects.requireNonNull(key);
		this.key = key;
	}
	@Override
	public ReactiveConstantNumber key() {
		return key;
	}
	@Override
	public ReactiveVersion version() {
		return new ReactiveVersionNumber(key.compute());
	}
}
