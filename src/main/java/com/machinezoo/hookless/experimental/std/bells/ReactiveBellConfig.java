// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.bells;

import java.util.*;
import com.machinezoo.hookless.experimental.*;
import com.machinezoo.hookless.experimental.std.caches.*;

public class ReactiveBellConfig implements ReactiveObjectConfig {
	private final ReactiveBell key;
	public ReactiveBellConfig(ReactiveBell key) {
		Objects.requireNonNull(key);
		this.key = key;
	}
	@Override
	public ReactiveBell key() {
		return key;
	}
	@Override
	public ReactiveCache cache() {
		return PermanentReactiveCache.DEFAULT;
	}
	@Override
	public ReactiveBellNode instantiate() {
		return new ReactiveBellNode(key);
	}
}
