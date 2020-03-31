// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.slf4j.*;
import com.machinezoo.hookless.time.*;
import com.machinezoo.noexception.*;

public class ReactivePoll<T> {
	private final Supplier<T> factory;
	private Duration interval = Duration.ofSeconds(1);
	public ReactivePoll<T> interval(Duration interval) {
		Objects.requireNonNull(interval);
		this.interval = interval;
		return this;
	}
	private List<Duration> retries = Collections.emptyList();
	public ReactivePoll<T> retries(List<Duration> retries) {
		Objects.requireNonNull(retries);
		for (Duration retry : retries)
			Objects.requireNonNull(retry);
		this.retries = retries;
		return this;
	}
	private boolean propagate;
	public ReactivePoll<T> propagate(boolean propagate) {
		this.propagate = propagate;
		return this;
	}
	private boolean started;
	private final ReactiveVariable<Object> nonce = OwnerTrace.of(new ReactiveVariable<>())
		.parent(this)
		.tag("role", "nonce")
		.target();
	private final ReactiveVariable<PollTimestamp> timestamp = OwnerTrace.of(new ReactiveVariable<>(new PollTimestamp()))
		.parent(this)
		.tag("role", "timestamp")
		.target();
	private final ReactiveVariable<T> latest = OwnerTrace.of(new ReactiveVariable<T>(new ReactiveValue<>(null, null, true)))
		.parent(this)
		.tag("role", "latest")
		.target();
	private static final Logger logger = LoggerFactory.getLogger(ReactivePoll.class);
	private final ReactiveThread thread = OwnerTrace
		.of(new ReactiveThread(Exceptions.log(logger).runnable(this::run)))
		.parent(this)
		.target();
	public ReactivePoll(Supplier<T> factory) {
		Objects.requireNonNull(factory);
		this.factory = factory;
		OwnerTrace.of(this).alias("poll");
	}
	public ExecutorService executor() {
		return thread.executor();
	}
	public ReactivePoll<T> executor(ExecutorService executor) {
		thread.executor(executor);
		return this;
	}
	public synchronized ReactivePoll<T> initial(T value) {
		if (started)
			throw new IllegalStateException();
		latest.set(value);
		return this;
	}
	public synchronized ReactivePoll<T> draft(T value) {
		if (started)
			throw new IllegalStateException();
		latest.value(new ReactiveValue<T>(value, true));
		return this;
	}
	public synchronized ReactivePoll<T> start() {
		if (!started) {
			started = true;
			thread.start();
		}
		return this;
	}
	public ReactivePoll<T> stop() {
		thread.stop();
		return this;
	}
	public T get() {
		return latest.get();
	}
	public ReactivePoll<T> invalidate() {
		nonce.set(new Object());
		return this;
	}
	private void run() {
		if (dirty()) {
			Object nonce = this.nonce.get();
			int retry = -1;
			try {
				T result;
				try (ReactiveScope.Computation computation = ReactiveScope.ignore()) {
					result = factory.get();
				}
				latest.set(result);
			} catch (Throwable e) {
				if (propagate)
					latest.value(new ReactiveValue<>(e));
				else
					Exceptions.log(logger).handle(e);
				retry = Math.min(retries.size(), timestamp.get().retry + 1);
			}
			timestamp.set(new PollTimestamp(Instant.now(), retry, nonce));
		}
	}
	private boolean dirty() {
		PollTimestamp timestamp = this.timestamp.get();
		if (timestamp.time == null)
			return true;
		if (timestamp.nonce != nonce.get())
			return true;
		Duration delay = timestamp.retry < 0 || timestamp.retry >= retries.size() ? interval : retries.get(timestamp.retry);
		return ReactiveInstant.now().compareTo(timestamp.time.plus(delay)) >= 0;
	}
	private static class PollTimestamp {
		final Instant time;
		final int retry;
		final Object nonce;
		PollTimestamp(Instant time, int retry, Object nonce) {
			this.time = time;
			this.retry = retry;
			this.nonce = nonce;
		}
		PollTimestamp() {
			this(null, -1, null);
		}
		@Override public boolean equals(Object obj) {
			if (!(obj instanceof PollTimestamp))
				return false;
			PollTimestamp other = (PollTimestamp)obj;
			return Arrays.equals(new Object[] { time, retry, nonce }, new Object[] { other.time, other.retry, other.nonce });
		}
		@Override public int hashCode() {
			return Objects.hash(time, retry, nonce);
		}
	}
	@Override public String toString() {
		return OwnerTrace.of(this) + " = " + latest.value();
	}
}
