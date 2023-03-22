// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import com.machinezoo.hookless.experimental.*;

public class HashedReactiveConstantObjectConfig<T> extends ReactiveConstantObjectConfig<T> {
	public HashedReactiveConstantObjectConfig(HashedReactiveConstantObject<T> key) {
		super(key);
	}
	@Override
	public HashedReactiveConstantObject<T> key() {
		return (HashedReactiveConstantObject<T>)super.key();
	}
	@Override
	public ReactiveVersion version() {
		return super.version().toHash();
	}
}
