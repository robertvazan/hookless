// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.bells;

import com.machinezoo.hookless.experimental.*;

public interface ReactiveBell extends ReactiveNodeKey {
	@Override
	default ReactiveNodeConfig reactiveConfig() {
		return new ReactiveBellConfig(this);
	}
	private ReactiveBellNode node() {
		return (ReactiveBellNode)ReactiveNode.of(this);
	}
	default void listen() {
		node().listen();
	}
	default void ring() {
		node().ring();
	}
}
