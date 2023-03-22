// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

public interface ReactiveNodeConfig {
	ReactiveNodeKey key();
	ReactiveCache cache();
	/*
	 * To be used by reactive caches only. App code should use ReactiveNode.of(key).
	 */
	ReactiveNode instantiate();
}
