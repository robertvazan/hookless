// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import java.math.*;
import java.time.*;
import java.time.temporal.*;
import com.machinezoo.stagean.*;

/**
 * Reactive version of {@link Instant}.
 */
@DraftApi("requires review")
@DraftCode("requires review")
@NoTests
@StubDocs
public class ReactiveInstant implements Comparable<ReactiveInstant> {
	final ReactiveClock clock;
	final Duration shift;
	ReactiveInstant(ReactiveClock clock, Duration shift) {
		this.clock = clock;
		this.shift = shift;
	}
	public static ReactiveInstant now() {
		return ReactiveClock.get().now();
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		else if (obj instanceof ReactiveInstant) {
			ReactiveInstant other = (ReactiveInstant)obj;
			clock.checkSame(other.clock);
			return shift.equals(other.shift);
		} else if (obj instanceof Instant)
			return compareTo((Instant)obj) == 0;
		else
			return false;
	}
	@Override
	public int hashCode() {
		return shift.hashCode();
	}
	@Override
	public int compareTo(ReactiveInstant other) {
		clock.checkSame(other.clock);
		return shift.compareTo(other.shift);
	}
	public int compareTo(Instant instant) {
		return clock.compareTo(instant.minus(shift));
	}
	public boolean isAfter(ReactiveInstant other) {
		clock.checkSame(other.clock);
		return shift.compareTo(other.shift) > 0;
	}
	public boolean isAfter(Instant instant) {
		return clock.isAfter(instant.minus(shift));
	}
	public boolean isBefore(ReactiveInstant other) {
		clock.checkSame(other.clock);
		return shift.compareTo(other.shift) < 0;
	}
	public boolean isBefore(Instant instant) {
		return clock.isBefore(instant.minus(shift));
	}
	public Instant plus(ShrinkingReactiveDuration duration) {
		clock.checkSame(duration.clock);
		return duration.zero.plus(shift);
	}
	public ReactiveInstant plus(Duration duration) {
		return new ReactiveInstant(clock, shift.plus(duration));
	}
	public ReactiveInstant plus(long amount, TemporalUnit unit) {
		return plus(Duration.of(amount, unit));
	}
	public ReactiveInstant plusSeconds(long seconds) {
		return plus(Duration.ofSeconds(seconds));
	}
	public ReactiveInstant plusMillis(long millis) {
		return plus(Duration.ofMillis(millis));
	}
	public ReactiveInstant plusNanos(long nanos) {
		return plus(Duration.ofNanos(nanos));
	}
	public Instant minus(GrowingReactiveDuration duration) {
		clock.checkSame(duration.clock);
		return duration.zero.plus(shift);
	}
	public ReactiveInstant minus(Duration duration) {
		return new ReactiveInstant(clock, shift.minus(duration));
	}
	public ReactiveInstant minus(long amount, TemporalUnit unit) {
		return minus(Duration.of(amount, unit));
	}
	public ReactiveInstant minusSeconds(long seconds) {
		return minus(Duration.ofSeconds(seconds));
	}
	public ReactiveInstant minusMillis(long millis) {
		return minus(Duration.ofMillis(millis));
	}
	public ReactiveInstant minusNanos(long nanos) {
		return minus(Duration.ofNanos(nanos));
	}
	public Instant truncatedTo(Duration unit) {
		if (unit.isNegative() || unit.isZero())
			throw new IllegalArgumentException("Can only truncate with positive unit");
		if (unit.compareTo(Duration.ofDays(1)) > 0)
			throw new IllegalArgumentException("Cannot truncate with unit longer that one day");
		long nanoUnit = unit.toNanos();
		if (Duration.ofDays(1).toNanos() % nanoUnit != 0)
			throw new IllegalArgumentException("Can only truncate with unit that divides day");
		Instant instant = clock.instant().plus(shift);
		Instant days = instant.truncatedTo(ChronoUnit.DAYS);
		long nanoTime = Duration.between(days, instant).toNanos();
		Instant truncated = days.plus(Duration.ofNanos(nanoTime / nanoUnit * nanoUnit));
		Instant constraint = truncated.minus(shift);
		clock.constrainLeftClosed(constraint);
		clock.constrainRightOpen(constraint.plus(unit));
		return truncated;
	}
	public Instant truncatedTo(TemporalUnit unit) {
		return truncatedTo(unit.getDuration());
	}
	public long getEpochSecond() {
		return truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
	}
	public long toEpochMilli() {
		return truncatedTo(ChronoUnit.MILLIS).toEpochMilli();
	}
	public long until(Instant end, Duration unit) {
		return ReactiveDuration.between(this, end).toUnits(unit);
	}
	public long until(Instant end, TemporalUnit unit) {
		return ReactiveDuration.between(this, end).toUnits(unit);
	}
	public long until(ReactiveInstant end, Duration unit) {
		Duration duration = ReactiveDuration.between(this, end);
		BigInteger big = ReactiveDuration.big(duration).divide(ReactiveDuration.big(unit));
		if (big.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 || big.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0)
			throw new ArithmeticException();
		return big.longValue();
	}
	public long until(ReactiveInstant end, TemporalUnit unit) {
		return until(end, unit.getDuration());
	}
	@Override
	public String toString() {
		return "now + " + shift.toString();
	}
}
