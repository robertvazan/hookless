// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

/*
 * With default configuration, object must satisfy requirements of ReactiveVersionObject.
 */
public interface HashedReactiveConstantObject<T> extends ReactiveConstantObject<T> {
	@Override
	default ReactiveConstantObjectConfig<T> reactiveConfig() {
		return new HashedReactiveConstantObjectConfig<>(this);
	}
}
