// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

public interface ReactiveConstantNumber extends ReactiveConstant {
	@Override
	default ReactiveConstantNumberConfig reactiveConfig() {
		return new ReactiveConstantNumberConfig(this);
	}
	long compute();
	default long getAsLong() {
		touch();
		return compute();
	}
	default int getAsInt() {
		return (int)getAsLong();
	}
}
