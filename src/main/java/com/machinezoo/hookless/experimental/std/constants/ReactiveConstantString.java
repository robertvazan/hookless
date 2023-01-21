// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

public interface ReactiveConstantString extends ReactiveConstant {
	@Override
	default ReactiveConstantStringConfig reactiveConfig() {
		return new ReactiveConstantStringConfig(this);
	}
	String compute();
	default String get() {
		touch();
		return compute();
	}
}
