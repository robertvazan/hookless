// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import java.math.*;
import java.time.*;
import java.time.temporal.*;
import com.machinezoo.stagean.*;

/**
 * Reactive version of {@link Duration}, positive (growing) variant.
 */
@DraftApi("requires review")
@DraftCode("requires review")
@NoTests
@StubDocs
public class GrowingReactiveDuration extends ReactiveDuration implements Comparable<GrowingReactiveDuration> {
	GrowingReactiveDuration(ReactiveClock clock, Instant zero) {
		super(clock, zero);
	}
	@Override
	public int compareTo(GrowingReactiveDuration other) {
		clock.checkSame(other.clock);
		return other.zero.compareTo(zero);
	}
	@Override
	public int compareTo(Duration duration) {
		return clock.compareTo(zero.plus(duration));
	}
	@Override
	public boolean isPositive() {
		return clock.isAfter(zero);
	}
	@Override
	public boolean isNegative() {
		return clock.isBefore(zero);
	}
	@Override
	public boolean isZero() {
		return clock.isAt(zero);
	}
	@Override
	public GrowingReactiveDuration plus(Duration duration) {
		return new GrowingReactiveDuration(clock, zero.minus(duration));
	}
	public Duration plus(ShrinkingReactiveDuration other) {
		return Duration.between(zero, other.zero);
	}
	@Override
	public GrowingReactiveDuration plus(long amount, TemporalUnit unit) {
		return plus(Duration.of(amount, unit));
	}
	@Override
	public GrowingReactiveDuration plusDays(long days) {
		return plus(Duration.ofDays(days));
	}
	@Override
	public GrowingReactiveDuration plusHours(long hours) {
		return plus(Duration.ofHours(hours));
	}
	@Override
	public GrowingReactiveDuration plusMinutes(long minutes) {
		return plus(Duration.ofMinutes(minutes));
	}
	@Override
	public GrowingReactiveDuration plusSeconds(long seconds) {
		return plus(Duration.ofSeconds(seconds));
	}
	@Override
	public GrowingReactiveDuration plusMillis(long millis) {
		return plus(Duration.ofMillis(millis));
	}
	@Override
	public GrowingReactiveDuration plusNanos(long nanos) {
		return plus(Duration.ofNanos(nanos));
	}
	@Override
	public GrowingReactiveDuration minus(Duration duration) {
		return plus(duration.negated());
	}
	public Duration minus(GrowingReactiveDuration other) {
		return Duration.between(zero, other.zero);
	}
	@Override
	public GrowingReactiveDuration minus(long amount, TemporalUnit unit) {
		return minus(Duration.of(amount, unit));
	}
	@Override
	public GrowingReactiveDuration minusDays(long days) {
		return minus(Duration.ofDays(days));
	}
	@Override
	public GrowingReactiveDuration minusHours(long hours) {
		return minus(Duration.ofHours(hours));
	}
	@Override
	public GrowingReactiveDuration minusMinutes(long minutes) {
		return minus(Duration.ofMinutes(minutes));
	}
	@Override
	public GrowingReactiveDuration minusSeconds(long seconds) {
		return minus(Duration.ofSeconds(seconds));
	}
	@Override
	public GrowingReactiveDuration minusMillis(long millis) {
		return minus(Duration.ofMillis(millis));
	}
	@Override
	public GrowingReactiveDuration minusNanos(long nanos) {
		return minus(Duration.ofNanos(nanos));
	}
	@Override
	public ShrinkingReactiveDuration negated() {
		return new ShrinkingReactiveDuration(clock, zero);
	}
	@Override
	public Duration truncatedTo(Duration unit) {
		if (unit.isNegative() || unit.isZero())
			throw new IllegalArgumentException("Can only truncate with positive unit");
		Duration duration = Duration.between(zero, clock.instant());
		BigInteger bigUnit = big(unit);
		Duration truncated = unbig(big(duration).divide(bigUnit).multiply(bigUnit));
		Instant constraint = zero.plus(truncated);
		if (!duration.isNegative()) {
			clock.constrainLeftClosed(constraint);
			clock.constrainRightOpen(constraint.plus(unit));
		} else {
			clock.constrainLeftOpen(constraint);
			clock.constrainRightClosed(constraint.minus(unit));
		}
		return truncated;
	}
	public Instant subtractFrom(ReactiveInstant instant) {
		return instant.minus(this);
	}
	public ReactiveInstant addTo(Instant instant) {
		return new ReactiveInstant(clock, Duration.between(zero, instant));
	}
	@Override
	public String toString() {
		return "now - " + zero.toString();
	}
}
