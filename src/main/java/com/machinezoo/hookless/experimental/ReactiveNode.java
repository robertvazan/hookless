// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

public interface ReactiveNode {
	ReactiveNodeKey key();
	static ReactiveNode of(ReactiveNodeKey key) {
		return key.reactiveConfig().cache().materialize(key);
	}
}
