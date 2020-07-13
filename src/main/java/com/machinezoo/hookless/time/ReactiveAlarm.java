// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import java.time.*;

/*
 * Alarm is an immutable version of ReactiveClock for indexing in AlarmIndex.
 * ReactiveClock must hold a reference to current ReactiveAlarm in order to protect it from GC.
 */
class ReactiveAlarm {
	/*
	 * Alarm's range of valid times is half-closed: [lower, upper).
	 */
	final Instant lower;
	final Instant upper;
	private final ReactiveClock clock;
	ReactiveAlarm(Instant lower, Instant upper, ReactiveClock clock) {
		this.lower = lower;
		this.upper = upper;
		this.clock = clock;
	}
	void ring() {
		clock.ring();
	}
	ReactiveAlarm constrainUpper(Instant time) {
		if (upper == null || time.isBefore(upper))
			return new ReactiveAlarm(lower, time, clock);
		else
			return this;
	}
	ReactiveAlarm constrainLower(Instant time) {
		if (lower == null || time.isAfter(lower))
			return new ReactiveAlarm(time, upper, clock);
		else
			return this;
	}
	@Override
	public String toString() {
		return "[" + (lower != null ? lower.toString() : "infinity") + ", " + (upper != null ? upper.toString() : "infinity") + ")";
	}
}
