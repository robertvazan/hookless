// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.function.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

public class ReactiveCache<T> {
	private static final Timer timer = Metrics.timer("hookless.cache.simple.evals");
	private static final Counter exceptionCount = Metrics.counter("hookless.cache.simple.exceptions");
	private final Supplier<T> factory;
	private final ReactiveVariable<Object> version = OwnerTrace
		.of(new ReactiveVariable<Object>()
			.keepalive(this))
		.parent(this)
		.target();
	private ReactivePins pins;
	private ReactiveTrigger trigger;
	private T last;
	private boolean valid;
	public ReactiveCache(Supplier<T> factory) {
		Objects.requireNonNull(factory);
		this.factory = factory;
		OwnerTrace.of(this).alias("cache");
	}
	public T get() {
		version.get();
		T result;
		boolean block;
		ReactiveScope scope = null;
		synchronized (this) {
			if (!valid) {
				scope = OwnerTrace.of(new ReactiveScope())
					.parent(this)
					.target();
				if (pins != null)
					scope.pins(pins);
				try (ReactiveScope.Computation computation = scope.enter()) {
					last = timer.record(factory);
				} catch (Throwable t) {
					version.set(new Object());
					exceptionCount.increment();
					throw t;
				} finally {
					pins = scope.blocked() ? scope.pins() : null;
				}
				valid = true;
				trigger = OwnerTrace
					.of(new ReactiveTrigger()
						.callback(this::invalidate))
					.parent(this)
					.target();
			}
			result = last;
			block = this.pins != null;
		}
		if (block)
			CurrentReactiveScope.block();
		if (scope != null)
			trigger.arm(scope.versions());
		return result;
	}
	private void invalidate() {
		PostLockQueue postlock = new PostLockQueue(this);
		postlock.run(() -> {
			if (trigger != null) {
				last = null;
				valid = false;
				trigger.close();
				trigger = null;
				postlock.post(() -> version.set(new Object()));
			}
		});
	}
	@Override public synchronized String toString() {
		return OwnerTrace.of(this) + " = " + Objects.toString(last);
	}
}
