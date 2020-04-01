// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.slf4j.*;
import com.machinezoo.noexception.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

public class ReactiveAsyncCache<T> {
	private static final Timer timer = Metrics.timer("hookless.cache.async.evals");
	private static final Counter exceptionCount = Metrics.counter("hookless.cache.async.exceptions");
	private static final Logger logger = LoggerFactory.getLogger(ReactiveAsyncCache.class);
	private final Supplier<T> factory;
	private Executor executor = ReactiveExecutor.instance();
	public ReactiveAsyncCache<T> executor(Executor executor) {
		Objects.requireNonNull(executor);
		this.executor = executor;
		return this;
	}
	private Timer.Sample sample;
	private final ReactiveVariable<Object> version = OwnerTrace
		.of(new ReactiveVariable<Object>()
			.keepalive(this))
		.parent(this)
		.target();
	private volatile CacheVars<T> vars = new CacheVars<T>(CacheState.INITIAL, new ReactiveValue<T>(null, null, true), null);
	@SuppressWarnings("unused") private ReactiveTrigger trigger;
	public ReactiveAsyncCache(Supplier<T> factory) {
		Objects.requireNonNull(factory);
		this.factory = factory;
		OwnerTrace.of(this).alias("acache");
	}
	public ReactiveAsyncCache<T> draft(T initial) {
		return initialize(initial, true);
	}
	public ReactiveAsyncCache<T> initial(T initial) {
		return initialize(initial, false);
	}
	public synchronized ReactiveAsyncCache<T> start() {
		if (vars.state == CacheState.INITIAL) {
			vars = vars.withState(CacheState.RUNNING);
			schedule();
		}
		return this;
	}
	public T get() {
		version.get();
		CacheVars<T> snapshot = vars;
		if (snapshot.state == CacheState.INITIAL || snapshot.state == CacheState.AWAITING_QUERIES || snapshot.state == CacheState.AWAITING_QUERIES_BACKLOGGED) {
			PostLockQueue postlock = new PostLockQueue(this);
			snapshot = postlock.eval(() -> {
				switch (vars.state) {
				case INITIAL:
					vars = vars.withState(CacheState.RUNNING);
					postlock.post(this::schedule);
					break;
				case AWAITING_QUERIES:
					vars = vars.withState(CacheState.IDLE);
					break;
				case AWAITING_QUERIES_BACKLOGGED:
					vars = vars.withState(CacheState.RUNNING);
					postlock.post(this::schedule);
					break;
				case RUNNING:
				case RUNNING_BACKLOGGED:
				case IDLE:
					break;
				}
				return vars;
			});
		}
		return snapshot.latest.get();
	}
	private synchronized ReactiveAsyncCache<T> initialize(T initial, boolean block) {
		if (vars.state != CacheState.INITIAL)
			throw new IllegalStateException();
		vars = new CacheVars<>(CacheState.INITIAL, new ReactiveValue<>(initial, vars.latest.blocking() && block), vars.pins);
		version.set(new Object());
		return this;
	}
	private void schedule() {
		sample = Timer.start(Clock.SYSTEM);
		executor.execute(Exceptions.log(logger).runnable(this::run));
	}
	private void run() {
		Timer.Sample sample = this.sample;
		this.sample = null;
		ReactiveScope scope = OwnerTrace.of(new ReactiveScope())
			.parent(this)
			.target();
		synchronized (this) {
			if (vars.pins != null)
				scope.pins(vars.pins);
		}
		ReactiveValue<T> updated;
		try (ReactiveScope.Computation computation = scope.enter()) {
			updated = ReactiveValue.capture(factory);
		}
		if (updated.exception() != null)
			exceptionCount.increment();
		@SuppressWarnings("resource") ReactiveTrigger trigger = OwnerTrace
			.of(new ReactiveTrigger()
				.callback(this::invalidate))
			.parent(this)
			.target();
		PostLockQueue postlock = new PostLockQueue(this);
		postlock.run(() -> {
			this.trigger = trigger;
			switch (vars.state) {
			case RUNNING:
				if (scope.blocked())
					vars = new CacheVars<>(CacheState.IDLE, vars.latest, scope.pins());
				else {
					vars = new CacheVars<>(CacheState.AWAITING_QUERIES, updated, null);
					postlock.post(() -> version.set(new Object()));
				}
				break;
			case RUNNING_BACKLOGGED:
				if (scope.blocked()) {
					vars = new CacheVars<>(CacheState.RUNNING, vars.latest, scope.pins());
					postlock.post(this::schedule);
				} else {
					vars = new CacheVars<>(CacheState.AWAITING_QUERIES_BACKLOGGED, updated, null);
					postlock.post(() -> version.set(new Object()));
				}
				break;
			default:
				throw new IllegalStateException();
			}
		});
		trigger.arm(scope.versions());
		sample.stop(timer);
	}
	private void invalidate() {
		trigger.close();
		PostLockQueue postlock = new PostLockQueue(this);
		postlock.run(() -> {
			switch (vars.state) {
			case RUNNING:
				vars = vars.withState(CacheState.RUNNING_BACKLOGGED);
				break;
			case AWAITING_QUERIES:
				vars = vars.withState(CacheState.AWAITING_QUERIES_BACKLOGGED);
				break;
			case IDLE:
				vars = vars.withState(CacheState.RUNNING);
				postlock.post(this::schedule);
				break;
			case RUNNING_BACKLOGGED:
			case AWAITING_QUERIES_BACKLOGGED:
			case INITIAL:
				break;
			}
		});
	}
	private static class CacheVars<T> {
		final CacheState state;
		final ReactiveValue<T> latest;
		final ReactivePins pins;
		CacheVars(CacheState state, ReactiveValue<T> latest, ReactivePins pins) {
			this.state = state;
			this.latest = latest;
			this.pins = pins;
		}
		CacheVars<T> withState(CacheState next) {
			return new CacheVars<>(next, latest, pins);
		}
	}
	private static enum CacheState {
		INITIAL,
		RUNNING,
		RUNNING_BACKLOGGED,
		AWAITING_QUERIES,
		AWAITING_QUERIES_BACKLOGGED,
		IDLE
	}
	@Override public String toString() {
		return OwnerTrace.of(this) + " = " + Objects.toString(vars.latest.exception(), Objects.toString(vars.latest.result()));
	}
}
