// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.blocking;

import com.machinezoo.hookless.experimental.*;

public record ReactiveBlocking() implements ReactiveSideEffect {
	@Override
	public ReactiveBlockingKey key() {
		return new ReactiveBlockingKey();
	}
	public static void block() {
		var computation = ReactiveStack.top();
		if (computation != null)
			computation.consume(new ReactiveBlocking());
	}
}
