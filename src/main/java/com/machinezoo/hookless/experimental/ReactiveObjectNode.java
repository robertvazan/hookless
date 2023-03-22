// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

public interface ReactiveObjectNode {
	ReactiveObject key();
	static ReactiveObjectNode of(ReactiveObject key) {
		return key.reactiveConfig().cache().materialize(key);
	}
}
