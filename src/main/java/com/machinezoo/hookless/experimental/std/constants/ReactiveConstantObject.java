// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import java.io.*;

/*
 * Object must be serializable a stringifiable.
 */
public interface ReactiveConstantObject<T extends Serializable> extends ReactiveConstant {
	@Override
	default ReactiveConstantObjectConfig<T> reactiveConfig() {
		return new ReactiveConstantObjectConfig<>(this);
	}
	T compute();
	default T get() {
		touch();
		return compute();
	}
}
