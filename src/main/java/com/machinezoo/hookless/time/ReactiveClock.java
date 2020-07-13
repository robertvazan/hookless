// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import java.time.*;
import java.util.concurrent.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.util.*;

class ReactiveClock implements Comparable<Instant> {
	private final Instant now = Instant.now();
	/*
	 * Lifetime of reactive clock is a bit tricky. Clock obviously stays alive during reactive computation that created it.
	 * This is ensured by having clock instance frozen in current reactive computation.
	 * After that, reactive clock should stay around as long as its reactive variable is referenced from some trigger.
	 * Once there is no such reference, there is no one to notify when the clock rings and therefore no need to keep it alive.
	 * We will use keepalive feature in reactive variable to ensure there's always a strong reference pointing at the clock.
	 */
	private final ReactiveVariable<Object> version = OwnerTrace
		.of(new ReactiveVariable<Object>()
			.keepalive(this))
		.parent(this)
		.target();
	private ReactiveAlarm alarm = new ReactiveAlarm(null, null, this);
	private ReactiveClock() {
		OwnerTrace.of(this)
			.alias("clock")
			.tag("freeze", now);
	}
	static ReactiveClock get() {
		/*
		 * Don't pin. Freeze. Pinning of reactive time is unsafe.
		 * It also causes time to behave oddly if lengthy blocking makes the pins long-lived.
		 */
		return CurrentReactiveScope.freeze(ClockKey.instance, ReactiveClock::new);
	}
	ReactiveInstant now() {
		return new ReactiveInstant(this, Duration.ZERO);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof ReactiveClock))
			return false;
		return ((ReactiveClock)obj).now.equals(now);
	}
	@Override
	public int hashCode() {
		return now.hashCode();
	}
	@Override
	public int compareTo(Instant time) {
		constrain(time);
		if (now.isAfter(time))
			return 1;
		constrain(time.plusNanos(1));
		if (now.isBefore(time))
			return -1;
		return 0;
	}
	boolean isBefore(Instant time) {
		constrain(time);
		return now.isBefore(time);
	}
	boolean isAfter(Instant time) {
		constrain(time.plusNanos(1));
		return now.isAfter(time);
	}
	boolean isAt(Instant time) {
		return compareTo(time) == 0;
	}
	Instant instant() {
		return now;
	}
	void checkSame(ReactiveClock other) {
		if (this != other)
			throw new IllegalArgumentException("Cannot mix different instances of " + ReactiveClock.class.getSimpleName());
	}
	void ring() {
		version.set(new Object());
	}
	void constrain(Instant time) {
		ReactiveAlarm previous = alarm;
		if (time.isAfter(now))
			alarm = previous.constrainUpper(time);
		else
			alarm = previous.constrainLower(time);
		if (alarm != previous) {
			/*
			 * Read the reactive variable before invoking AlarmScheduler, which may invalidate the variable immediately.
			 */
			version.get();
			AlarmScheduler.instance.monitor(alarm, previous);
		}
	}
	void constrainLeftClosed(Instant time) {
		constrain(time);
	}
	void constrainRightOpen(Instant time) {
		constrain(time);
	}
	void constrainLeftOpen(Instant time) {
		constrain(time.plusNanos(1));
	}
	void constrainRightClosed(Instant time) {
		constrain(time.plusNanos(1));
	}
	private static class ClockKey {
		/*
		 * This is preferable to using ReactiveClock.class,
		 * because
		 */
		static ClockKey instance = new ClockKey();
		/*
		 * Pregenerated hash code speeds up lookups in the pinned object map.
		 */
		final int hashCode = ThreadLocalRandom.current().nextInt();
		@Override
		public int hashCode() {
			return hashCode;
		}
	}
	@Override
	public String toString() {
		return OwnerTrace.of(this) + " = " + alarm;
	}
}
