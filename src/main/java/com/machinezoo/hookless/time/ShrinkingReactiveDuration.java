// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import java.math.*;
import java.time.*;
import java.time.temporal.*;
import com.machinezoo.stagean.*;

/**
 * Reactive version of {@link Duration}, negative (shrinking) variant.
 */
@DraftApi("requires review")
@DraftCode("requires review")
@NoTests
@StubDocs
public class ShrinkingReactiveDuration extends ReactiveDuration implements Comparable<ShrinkingReactiveDuration> {
	ShrinkingReactiveDuration(ReactiveClock clock, Instant zero) {
		super(clock, zero);
	}
	@Override
	public int compareTo(ShrinkingReactiveDuration other) {
		clock.checkSame(other.clock);
		return zero.compareTo(other.zero);
	}
	@Override
	public int compareTo(Duration duration) {
		return -clock.compareTo(zero.minus(duration));
	}
	@Override
	public boolean isPositive() {
		return clock.isBefore(zero);
	}
	@Override
	public boolean isNegative() {
		return clock.isAfter(zero);
	}
	@Override
	public boolean isZero() {
		return clock.isAt(zero);
	}
	@Override
	public ShrinkingReactiveDuration plus(Duration duration) {
		return new ShrinkingReactiveDuration(clock, zero.plus(duration));
	}
	public Duration plus(GrowingReactiveDuration other) {
		return Duration.between(other.zero, zero);
	}
	@Override
	public ShrinkingReactiveDuration plus(long amount, TemporalUnit unit) {
		return plus(Duration.of(amount, unit));
	}
	@Override
	public ShrinkingReactiveDuration plusDays(long days) {
		return plus(Duration.ofDays(days));
	}
	@Override
	public ShrinkingReactiveDuration plusHours(long hours) {
		return plus(Duration.ofHours(hours));
	}
	@Override
	public ShrinkingReactiveDuration plusMinutes(long minutes) {
		return plus(Duration.ofMinutes(minutes));
	}
	@Override
	public ShrinkingReactiveDuration plusSeconds(long seconds) {
		return plus(Duration.ofSeconds(seconds));
	}
	@Override
	public ShrinkingReactiveDuration plusMillis(long millis) {
		return plus(Duration.ofMillis(millis));
	}
	@Override
	public ShrinkingReactiveDuration plusNanos(long nanos) {
		return plus(Duration.ofNanos(nanos));
	}
	@Override
	public ShrinkingReactiveDuration minus(Duration duration) {
		return plus(duration.negated());
	}
	public Duration minus(ShrinkingReactiveDuration other) {
		return Duration.between(other.zero, zero);
	}
	@Override
	public ShrinkingReactiveDuration minus(long amount, TemporalUnit unit) {
		return minus(Duration.of(amount, unit));
	}
	@Override
	public ShrinkingReactiveDuration minusDays(long days) {
		return minus(Duration.ofDays(days));
	}
	@Override
	public ShrinkingReactiveDuration minusHours(long hours) {
		return minus(Duration.ofHours(hours));
	}
	@Override
	public ShrinkingReactiveDuration minusMinutes(long minutes) {
		return minus(Duration.ofMinutes(minutes));
	}
	@Override
	public ShrinkingReactiveDuration minusSeconds(long seconds) {
		return minus(Duration.ofSeconds(seconds));
	}
	@Override
	public ShrinkingReactiveDuration minusMillis(long millis) {
		return minus(Duration.ofMillis(millis));
	}
	@Override
	public ShrinkingReactiveDuration minusNanos(long nanos) {
		return minus(Duration.ofNanos(nanos));
	}
	@Override
	public GrowingReactiveDuration negated() {
		return new GrowingReactiveDuration(clock, zero);
	}
	@Override
	public Duration truncatedTo(Duration unit) {
		if (unit.isNegative() || unit.isZero())
			throw new IllegalArgumentException("Can only truncate with positive unit");
		Duration duration = Duration.between(clock.instant(), zero);
		BigInteger bigUnit = big(unit);
		Duration truncated = unbig(big(duration).divide(bigUnit).multiply(bigUnit));
		Instant constraint = zero.minus(truncated);
		if (!duration.isNegative()) {
			clock.constrainLeftOpen(constraint.minus(unit));
			clock.constrainRightClosed(constraint);
		} else {
			clock.constrainLeftClosed(constraint);
			clock.constrainRightOpen(constraint.plus(unit));
		}
		return truncated;
	}
	public Instant addTo(ReactiveInstant instant) {
		return instant.plus(this);
	}
	public ReactiveInstant subtractFrom(Instant instant) {
		return new ReactiveInstant(clock, Duration.between(zero, instant));
	}
	@Override
	public String toString() {
		return zero.toString() + " - now";
	}
}
