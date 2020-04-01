// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.slf4j.*;
import com.machinezoo.noexception.*;

public class ReactiveGenerator<T> {
	public ReactiveGenerator() {
	}
	private Supplier<T> factory;
	public Supplier<T> factory() {
		return factory;
	}
	public ReactiveGenerator<T> factory(Supplier<T> factory) {
		this.factory = factory;
		return this;
	}
	private Executor executor = ReactiveExecutor.instance();
	public Executor executor() {
		return executor;
	}
	public ReactiveGenerator<T> executor(Executor executor) {
		this.executor = executor;
		return this;
	}
	private T item;
	private GeneratorState state = GeneratorState.INITIAL;
	private ReactivePins pins;
	@SuppressWarnings("unused") private ReactiveTrigger trigger;
	private final ReactiveVariable<Object> version = OwnerTrace
		.of(new ReactiveVariable<Object>()
			.keepalive(this))
		.parent(this)
		.target();
	private static final Logger logger = LoggerFactory.getLogger(ReactiveGenerator.class);
	public ReactiveGenerator(Supplier<T> factory) {
		this.factory = factory;
		OwnerTrace.of(this).alias("generator");
	}
	public ReactiveGenerator<T> start() {
		PostLockQueue postlock = new PostLockQueue(this);
		postlock.run(() -> {
			if (state == GeneratorState.INITIAL) {
				state = GeneratorState.RUNNING;
				postlock.post(() -> version.set(new Object()));
				postlock.post(this::schedule);
			}
		});
		return this;
	}
	public synchronized ReactiveGenerator<T> stop() {
		PostLockQueue postlock = new PostLockQueue(this);
		postlock.run(() -> {
			switch (state) {
			case AVAILABLE:
			case INVALIDATED:
				state = GeneratorState.LAST;
				postlock.post(() -> version.set(new Object()));
				break;
			case STOPPED:
				break;
			default:
				state = GeneratorState.STOPPED;
				postlock.post(() -> version.set(new Object()));
				break;
			}
		});
		return this;
	}
	public T peek() {
		return read(false);
	}
	public T element() {
		return read(true);
	}
	public T poll() {
		return dequeue(false);
	}
	public T remove() {
		return dequeue(true);
	}
	private T read(boolean required) {
		version.get();
		synchronized (this) {
			switch (state) {
			case AVAILABLE:
			case INVALIDATED:
			case LAST:
				return item;
			case IDLE:
			case RUNNING:
			case BACKLOGGED:
			case STOPPED:
				if (required)
					throw new NoSuchElementException();
				else
					return null;
			default:
				throw new IllegalStateException();
			}
		}
	}
	private T dequeue(boolean required) {
		version.get();
		PostLockQueue postlock = new PostLockQueue(this);
		return postlock.eval(() -> {
			switch (state) {
			case AVAILABLE:
				T result = item;
				item = null;
				state = GeneratorState.IDLE;
				postlock.post(() -> version.set(new Object()));
				return result;
			case INVALIDATED:
				T latest = item;
				item = null;
				state = GeneratorState.RUNNING;
				postlock.post(() -> version.set(new Object()));
				postlock.post(this::schedule);
				return latest;
			case LAST:
				T last = item;
				item = null;
				state = GeneratorState.STOPPED;
				postlock.post(() -> version.set(new Object()));
				return last;
			case IDLE:
			case RUNNING:
			case BACKLOGGED:
			case STOPPED:
				if (required)
					throw new NoSuchElementException();
				else
					return null;
			default:
				throw new IllegalStateException();
			}
		});
	}
	private void schedule() {
		executor.execute(Exceptions.log(logger).runnable(this::refresh));
	}
	private void refresh() {
		ReactiveScope scope = OwnerTrace.of(new ReactiveScope())
			.parent(this)
			.target();
		synchronized (this) {
			if (pins != null)
				scope.pins(pins);
			pins = null;
		}
		T updated = null;
		boolean failed = false;
		try (ReactiveScope.Computation computation = scope.enter()) {
			updated = factory.get();
		} catch (Throwable e) {
			Exceptions.log(logger).handle(e);
			failed = true;
		}
		@SuppressWarnings("resource") ReactiveTrigger trigger = OwnerTrace
			.of(new ReactiveTrigger()
				.callback(this::invalidate))
			.parent(this)
			.target();
		boolean failedFinal = failed;
		T updatedFinal = updated;
		PostLockQueue postlock = new PostLockQueue(this);
		postlock.run(() -> {
			switch (state) {
			case RUNNING:
				if (scope.blocked()) {
					state = GeneratorState.IDLE;
					pins = scope.pins();
				} else if (failedFinal)
					state = GeneratorState.IDLE;
				else {
					item = updatedFinal;
					state = GeneratorState.AVAILABLE;
					postlock.post(() -> version.set(new Object()));
				}
				break;
			case BACKLOGGED:
				if (scope.blocked()) {
					state = GeneratorState.RUNNING;
					pins = scope.pins();
					postlock.post(this::schedule);
				} else if (failedFinal) {
					state = GeneratorState.RUNNING;
					postlock.post(this::schedule);
				} else {
					item = updatedFinal;
					state = GeneratorState.INVALIDATED;
					postlock.post(() -> version.set(new Object()));
				}
				break;
			case STOPPED:
				break;
			default:
				throw new IllegalStateException();
			}
			this.trigger = trigger;
		});
		trigger.arm(scope.versions());
	}
	private void invalidate() {
		trigger.close();
		PostLockQueue postlock = new PostLockQueue(this);
		postlock.run(() -> {
			switch (state) {
			case AVAILABLE:
				state = GeneratorState.INVALIDATED;
				break;
			case IDLE:
				state = GeneratorState.RUNNING;
				postlock.post(this::schedule);
				break;
			case RUNNING:
				state = GeneratorState.BACKLOGGED;
				break;
			case BACKLOGGED:
			case INVALIDATED:
			case LAST:
			case STOPPED:
				break;
			default:
				throw new IllegalStateException();
			}
		});
	}
	private static enum GeneratorState {
		INITIAL,
		RUNNING,
		BACKLOGGED,
		AVAILABLE,
		IDLE,
		INVALIDATED,
		LAST,
		STOPPED
	}
	@Override public synchronized String toString() {
		boolean available = state == GeneratorState.AVAILABLE || state == GeneratorState.INVALIDATED || state == GeneratorState.LAST;
		return OwnerTrace.of(this) + ": " + (!available ? "(empty)" : Objects.toString(item));
	}
}
