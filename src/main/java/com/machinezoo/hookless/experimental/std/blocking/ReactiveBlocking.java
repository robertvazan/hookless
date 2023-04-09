// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.blocking;

import com.machinezoo.hookless.experimental.*;

public record ReactiveBlocking() implements ReactiveSideEffect {
	@Override
	public ReactiveBlockingKey key() {
		return ReactiveBlockingKey.INSTANCE;
	}
	@Override
	public ReactiveSideEffect merge(ReactiveSideEffect other) {
		if (!(other instanceof ReactiveBlocking))
			throw new IllegalArgumentException();
		return this;
	}
	public static void block() {
		var computation = ReactiveStack.top();
		if (computation != null)
			computation.consume(new ReactiveBlocking());
	}
	public static boolean blocking() {
		var computation = ReactiveStack.top();
		if (computation == null)
			return false;
		return computation.effect(ReactiveBlockingKey.INSTANCE) != null;
	}
}
