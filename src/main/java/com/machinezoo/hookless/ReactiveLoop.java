// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import com.machinezoo.noexception.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

/**
 * A loop that iterates when data changes.
 * Once {@link #start()} is called, {@link ReactiveLoop} runs once and collects dependencies on reactive data during that run.
 * When the reactive data changes, {@link ReactiveLoop} runs again and collects new set of dependencies.
 * This continues until either {@link #stop()}, {@link #complete(Object)}, or {@link #fail(Throwable)} is called.
 * Started {@link ReactiveLoop} is not garbage-collected until it is explicitly stopped by one of these methods.
 * <p>
 * Body of the loop can be provided either by overriding {@link #run()} or by setting {@link #body(Runnable)}.
 * If the loop body throws an exception, it has the same effect as calling {@link #fail(Throwable)}.
 * Loop body is executed by configured {@link #executor()}, which is compute-optimized by default.
 * Non-default {@link #executor(ExecutorService)} must be specified if loop body can block.
 * <p>
 * It is possible to wait for the loop to complete by monitoring loop's {@link #future()} (optionally using {@link ReactiveFuture}).
 * The kind of termination method used ({@link #stop()}, {@link #complete(Object)}, or {@link #fail(Throwable)}) determines final state of the {@link #future()}.
 *
 * @x.reactivity {@link ReactiveLoop} is a reactive computation. It runs when changes are made to reactive data referenced in previous iteration of the loop.
 * @x.threads All methods of this class are thread-safe.
 * @param <T>
 *            Return type of the loop, i.e. type of loop's {@link #future()}. Specify {@link Void} if the loop doesn't return anything.
 */
public class ReactiveLoop<T> {
	private static final Timer timer = Metrics.timer("hookless.loop.evals");
	private static final Counter exceptionCount = Metrics.counter("hookless.loop.exceptions");
	private static final Set<ReactiveLoop<?>> running =
		Metrics.gaugeCollectionSize("hookless.loop.strong", Collections.emptyList(), Collections.synchronizedSet(new HashSet<>()));
	private static final Logger logger = LoggerFactory.getLogger(ReactiveLoop.class);
	private boolean weak;
	private Runnable body = () -> {
	};
	private ExecutorService executor = ReactiveExecutor.instance();
	private boolean started;
	private boolean stopped;
	private ReactivePins pins;
	private ReactiveTrigger trigger;
	private final CompletableFuture<T> future = new CompletableFuture<>();
	private Timer.Sample sample;
	/**
	 * Creates new {@link ReactiveLoop} with empty loop body.
	 * Newly created {@link ReactiveLoop} can still be garbage-collected until {@link #start()} is called. It is therefore safe to abandon loops that were not started.
	 * 
	 * @x.lifecycle {@link ReactiveLoop} is not started immediately. Call {@link #start()} to start the loop.
	 * @see #start()
	 */
	public ReactiveLoop() {
		OwnerTrace.of(this).alias("loop");
	}
	/**
	 * Body of the loop specified as {@link Runnable}.
	 * This is an empty {@link Runnable} by default and it is never {@code null}.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.reactivity Loop body is executed in context of reactive computation. Changes in reactive data accessed in the loop body will trigger new iteration of the loop.
	 * @return Loop's body.
	 * @see #body(Runnable)
	 * @see #run()
	 */
	public synchronized Runnable body() {
		return body;
	}
	/**
	 * Sets new body of the loop.
	 * This method is an alternative to overriding {@link #run()}.
	 * Body specified here is ignored if derived class overrides {@link #run()}.
	 * For I/O-bound or time-consuming loop body, non-default {@link ExecutorService} must be specified via {@link #executor(ExecutorService)}.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.reactivity Loop body is executed in context of reactive computation. Changes in reactive data accessed in the loop body will trigger new iteration of the loop.
	 * @x.lifecycle This method can be called only before the loop is started.
	 * @param body
	 *            New loop body.
	 * @return {@code this}
	 * @throws NullPointerException
	 *             The {@code body} parameter is {@code null}.
	 * @throws IllegalStateException
	 *             The loop is already started.
	 * @see #body()
	 * @see #run()
	 * @see #executor(ExecutorService)
	 */
	public synchronized ReactiveLoop<T> body(Runnable body) {
		Objects.requireNonNull(body);
		if (started)
			throw new IllegalStateException();
		this.body = body;
		return this;
	}
	public synchronized boolean weak() {
		return weak;
	}
	public synchronized ReactiveLoop<T> weak(boolean weak) {
		if (started)
			throw new IllegalStateException();
		this.weak = weak;
		return this;
	}
	/**
	 * Loop's {@link ExecutorService} where loop body runs.
	 * Default executor is compute-optimized.
	 * For I/O-bound or time-consuming loop body, non-default {@link ExecutorService} must be specified via {@link #executor(ExecutorService)}.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @return Loop's current {@link ExecutorService}.
	 * @see #executor(ExecutorService)
	 */
	public synchronized ExecutorService executor() {
		return executor;
	}
	/**
	 * Sets new {@link ExecutorService} where the loop body will run.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.lifecycle This method can be called only before the loop is started.
	 * @param executor
	 *            New {@link ExecutorService}.
	 * @return {@code this}
	 * @throws NullPointerException
	 *             The {@code executor} parameter is {@code null}.
	 * @throws IllegalStateException
	 *             The loop is already started.
	 * @see #executor()
	 */
	public synchronized ReactiveLoop<T> executor(ExecutorService executor) {
		Objects.requireNonNull(executor);
		if (started)
			throw new IllegalStateException();
		this.executor = executor;
		return this;
	}
	/**
	 * Loop's {@link CompletableFuture} that can be queried whether the loop has finished and with what result.
	 * When {@link #complete(Object)} is called, this future completes with the specified result.
	 * When {@link #fail(Throwable)} is called, this future completes exceptionally with the specified exception.
	 * When {@link #stop()} is called, this future completes with {@code null} result.
	 * {@link ReactiveFuture} can be used to query the {@link CompletableFuture} as if it is reactive.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.reactivity This method and the returned {@link CompletableFuture} are NOT reactive. Reactive behavior can be obtained by wrapping the future in {@link ReactiveFuture}.
	 * @return Loop's {@link CompletableFuture}.
	 * @see #stop()
	 * @see #complete(Object)
	 * @see #fail(Throwable)
	 * @see ReactiveFuture
	 */
	public CompletableFuture<T> future() {
		return future;
	}
	/**
	 * Starts the loop.
	 * This method triggers execution of the first iteration of the loop on the specified {@link #executor()}.
	 * The loop will continue running until {@link #stop()}, {@link #complete(Object)}, or {@link #fail(Throwable)} is called.
	 * Running {@link ReactiveLoop} cannot be garbage-collected until it is stopped.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.lifecycle This method starts the loop. If the loop has been already started, this method has no effect. Stopped loop cannot be restarted.
	 * @return The same as {@link #future()}.
	 * @see #future()
	 * @see #executor()
	 * @see #stop()
	 * @see #complete(Object)
	 * @see #fail(Throwable)
	 */
	public synchronized CompletableFuture<T> start() {
		if (!started) {
			started = true;
			if (!weak)
				running.add(this);
			schedule();
		}
		return future;
	}
	/**
	 * Stops the loop and completes {@link #future()} with {@link CancellationException}.
	 * Once stopped, {@link ReactiveLoop} becomes eligible for garbage collection.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.lifecycle This method stops the loop. If the loop has been already stopped, this method has no effect.
	 *              If the loop has not been started yet, it is marked as stopped without ever running a single iteration.
	 * @return {@code this}
	 * @see #start()
	 * @see #future()
	 * @see #complete(Object)
	 * @see #fail(Throwable)
	 */
	public ReactiveLoop<T> stop() {
		return terminate(() -> future.completeExceptionally(new CancellationException()));
	}
	/**
	 * Stops the loop and completes {@link #future()} with the specified result.
	 * Once stopped, {@link ReactiveLoop} becomes eligible for garbage collection.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.lifecycle This method stops the loop. If the loop has been already stopped, this method has no effect.
	 *              If the loop has not been started yet, it is marked as stopped without ever running a single iteration.
	 * @param result
	 *            Result with which {@link #future()} completes.
	 * @return {@code this}
	 * @see #start()
	 * @see #future()
	 * @see #stop()
	 * @see #fail(Throwable)
	 */
	public ReactiveLoop<T> complete(T result) {
		return terminate(() -> future.complete(result));
	}
	/**
	 * Stops the loop and completes {@link #future()} exceptionally with the specified exception.
	 * Once stopped, {@link ReactiveLoop} becomes eligible for garbage collection.
	 * 
	 * @x.threads This method can be safely called from multiple threads.
	 * @x.lifecycle This method stops the loop. If the loop has been already stopped, this method has no effect.
	 *              If the loop has not been started yet, it is marked as stopped without ever running a single iteration.
	 * @param exception
	 *            Exception with which {@link #future()} completes exceptionally.
	 * @return {@code this}
	 * @see #start()
	 * @see #future()
	 * @see #complete(Object)
	 * @see #stop()
	 */
	public ReactiveLoop<T> fail(Throwable exception) {
		return terminate(() -> future.completeExceptionally(exception));
	}
	/**
	 * Loop body.
	 * Derived class can override this method to specify loop body, which is an alternative to specifying loop body via {@link #body(Runnable)}.
	 * By default, this method just invokes {@link #body()}.
	 * For I/O-bound or time-consuming loop body, non-default {@link ExecutorService} must be specified via {@link #executor(ExecutorService)}.
	 * This method should never be called directly.
	 * 
	 * @x.reactivity Loop body is executed in context of reactive computation. Changes in reactive data accessed in the loop body will trigger new iteration of the loop.
	 * @see #body(Runnable)
	 * @see #executor(ExecutorService)
	 */
	protected void run() {
		body.run();
	}
	private void iterate() {
		Timer.Sample sample = this.sample;
		this.sample = null;
		try {
			ReactiveScope scope = OwnerTrace.of(new ReactiveScope())
				.parent(this)
				.target();
			if (pins != null)
				scope.pins(pins);
			pins = null;
			try (ReactiveScope.Computation computation = scope.enter()) {
				try {
					run();
				} catch (Throwable e) {
					exceptionCount.increment();
					if (!scope.blocked())
						throw e;
				}
			}
			if (!stopped) {
				if (scope.blocked())
					pins = scope.pins();
				trigger = OwnerTrace
					.of(new ReactiveTrigger()
						.callback(this::invalidate))
					.parent(this)
					.target();
				trigger.arm(scope.versions());
			}
		} catch (Throwable e) {
			fail(e);
		} finally {
			sample.stop(timer);
		}
	}
	private synchronized void invalidate() {
		trigger.close();
		schedule();
	}
	private void schedule() {
		if (!stopped) {
			sample = Timer.start(Clock.SYSTEM);
			executor.submit(Exceptions.log(logger).runnable(this::iterate));
		}
	}
	private synchronized ReactiveLoop<T> terminate(Runnable mode) {
		if (!stopped) {
			started = stopped = true;
			if (!weak)
				running.remove(this);
			mode.run();
		}
		return this;
	}
	@Override public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
