// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

public interface ReactiveNodeConfig {
	ReactiveNodeKey key();
	ReactiveCache cache();
	ReactiveNode instantiate();
}
