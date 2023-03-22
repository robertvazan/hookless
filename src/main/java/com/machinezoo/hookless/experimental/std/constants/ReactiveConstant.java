// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import com.machinezoo.hookless.experimental.*;

public interface ReactiveConstant extends ReactiveData {
	@Override
	ReactiveConstantConfig reactiveConfig();
	default void touch() {
		new ReactiveConstantNode(this).track();
	}
}
