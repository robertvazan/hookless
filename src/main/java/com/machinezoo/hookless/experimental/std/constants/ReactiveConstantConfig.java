// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import com.machinezoo.hookless.experimental.*;
import com.machinezoo.hookless.experimental.std.caches.*;

public interface ReactiveConstantConfig extends ReactiveNodeConfig {
	@Override
	ReactiveConstant key();
	ReactiveVersion version();
	@Override
	default ReactiveCache cache() {
		return TransientReactiveCache.DEFAULT;
	}
	@Override
	default ReactiveConstantNode instantiate() {
		return new ReactiveConstantNode(key());
	}
}
