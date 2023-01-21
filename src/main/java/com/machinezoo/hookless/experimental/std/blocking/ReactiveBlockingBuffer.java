// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.blocking;

import com.machinezoo.hookless.experimental.*;

public class ReactiveBlockingBuffer implements ReactiveSideEffectBuffer {
	private boolean blocking;
	@Override
	public ReactiveBlockingKey key() {
		return new ReactiveBlockingKey();
	}
	@Override
	public void merge(ReactiveSideEffect effect) {
		if (effect instanceof ReactiveBlocking)
			blocking = true;
		else
			throw new IllegalArgumentException();
	}
	@Override
	public ReactiveSideEffect build() {
		return blocking ? new ReactiveBlocking() : null;
	}
}
