// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.blocking;

import com.machinezoo.hookless.experimental.*;

public record ReactiveBlockingKey() implements ReactiveSideEffectKey {
	@Override
	public ReactiveBlockingBuffer accumulate() {
		return new ReactiveBlockingBuffer();
	}
}
