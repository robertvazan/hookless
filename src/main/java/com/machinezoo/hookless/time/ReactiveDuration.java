// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import java.math.*;
import java.time.*;
import java.time.temporal.*;
import com.machinezoo.stagean.*;

/**
 * Reactive version of {@link Duration}.
 */
@DraftApi("requires review")
@DraftCode("requires review")
@NoTests
@StubDocs
public abstract class ReactiveDuration {
	final ReactiveClock clock;
	final Instant zero;
	ReactiveDuration(ReactiveClock clock, Instant zero) {
		this.clock = clock;
		this.zero = zero;
	}
	public abstract int compareTo(Duration duration);
	public abstract boolean isNegative();
	public abstract boolean isPositive();
	public abstract boolean isZero();
	public abstract ReactiveDuration plus(Duration duration);
	public abstract ReactiveDuration plus(long amount, TemporalUnit unit);
	public abstract ReactiveDuration plusDays(long days);
	public abstract ReactiveDuration plusHours(long hours);
	public abstract ReactiveDuration plusMinutes(long minutes);
	public abstract ReactiveDuration plusSeconds(long seconds);
	public abstract ReactiveDuration plusMillis(long millis);
	public abstract ReactiveDuration plusNanos(long nanos);
	public abstract ReactiveDuration minus(Duration duration);
	public abstract ReactiveDuration minus(long amount, TemporalUnit unit);
	public abstract ReactiveDuration minusDays(long days);
	public abstract ReactiveDuration minusHours(long hours);
	public abstract ReactiveDuration minusMinutes(long minutes);
	public abstract ReactiveDuration minusSeconds(long seconds);
	public abstract ReactiveDuration minusMillis(long millis);
	public abstract ReactiveDuration minusNanos(long nanos);
	public abstract ReactiveDuration negated();
	public abstract Duration truncatedTo(Duration unit);
	public static Duration between(ReactiveInstant start, ReactiveInstant end) {
		start.clock.checkSame(end.clock);
		return end.shift.minus(start.shift);
	}
	public static ShrinkingReactiveDuration between(ReactiveInstant start, Instant end) {
		return new ShrinkingReactiveDuration(start.clock, end.minus(start.shift));
	}
	public static GrowingReactiveDuration between(Instant start, ReactiveInstant end) {
		return new GrowingReactiveDuration(end.clock, start.minus(end.shift));
	}
	public static Duration between(Instant start, Instant end) {
		return Duration.between(start, end);
	}
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ReactiveDuration))
			return false;
		ReactiveDuration other = (ReactiveDuration)obj;
		if ((this instanceof GrowingReactiveDuration) != (other instanceof GrowingReactiveDuration))
			return false;
		clock.checkSame(other.clock);
		return zero.equals(other.zero);
	}
	@Override
	public int hashCode() {
		return zero.hashCode();
	}
	public Duration truncatedTo(TemporalUnit unit) {
		return truncatedTo(unit.getDuration());
	}
	public long toUnits(Duration unit) {
		BigInteger big = big(truncatedTo(unit)).divide(big(unit));
		if (big.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 || big.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0)
			throw new ArithmeticException();
		return big.longValue();
	}
	public long toUnits(TemporalUnit unit) {
		return toUnits(unit.getDuration());
	}
	public long toDays() {
		return toUnits(ChronoUnit.DAYS);
	}
	public long toHours() {
		return toUnits(ChronoUnit.HOURS);
	}
	public long toMinutes() {
		return toUnits(ChronoUnit.MINUTES);
	}
	public long getSeconds() {
		return toUnits(ChronoUnit.SECONDS);
	}
	public long toMillis() {
		return toUnits(ChronoUnit.MILLIS);
	}
	static BigInteger big(Duration duration) {
		return BigInteger.valueOf(duration.getSeconds()).multiply(BigInteger.valueOf(1_000_000_000)).add(BigInteger.valueOf(duration.getNano()));
	}
	static Duration unbig(BigInteger big) {
		BigInteger[] divrem = big.divideAndRemainder(BigInteger.valueOf(1_000_000_000));
		return Duration.ofSeconds(divrem[0].longValue(), divrem[1].longValue());
	}
}
