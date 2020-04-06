// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.machinezoo.hookless.utils.*;

/*
 * Reactive computation graph needs intermediate nodes that are both consumers and sources of reactivity.
 * Intermediate nodes mostly serve as compute caches improving performance, a kind of reactive memoization.
 * Reactive worker allows applications to easily define such intermediate nodes in the reactive computation graph.
 * Contrary to the synchronous reactive lazy, asynchronousness of reactive workers allows them
 * to behave like normal reactive computations and to shield dependents from invalidations that do not change the result.
 * 
 * The worker metaphor comes from workers in many UI toolkits. These workers run on a thread contrary to synchronous memoization.
 * Unlike non-reactive UI workers, reactive worker keeps updating the result whenever dependencies change.
 * 
 * Reactive worker is similar to reactive thread, but it is optimized for value-returning reactive computations,
 * which among other things allows it to pause the reactive computation when nobody reads the result.
 * This similarity allows us to implement reactive worker as an instance of reactive thread.
 */
public class ReactiveWorker<T> implements Supplier<T> {
	/*
	 * Worker has a configuration phase when its properties can be changed. It is started automatically on first access.
	 */
	private boolean started;
	void ensureNotStarted() {
		if (started)
			throw new IllegalStateException();
	}
	/*
	 * Just like in reactive threads, we give the application choice between override and lambda via constructor or setter.
	 */
	private Supplier<T> supplier = () -> null;
	public synchronized ReactiveWorker<T> supplier(Supplier<T> supplier) {
		Objects.requireNonNull(supplier);
		ensureNotStarted();
		this.supplier = supplier;
		return this;
	}
	public synchronized Supplier<T> supplier() {
		return supplier;
	}
	public ReactiveWorker() {
		OwnerTrace.of(this).alias("worker");
	}
	public ReactiveWorker(Supplier<T> supplier) {
		/*
		 * Ensure OwnerTrace is set up.
		 */
		this();
		supplier(supplier);
	}
	protected T supply() {
		return supplier.get();
	}
	/*
	 * As an intermediate node in the reactive computation graph, we need reactive variable that will hold worker's output.
	 */
	private final ReactiveVariable<T> output = OwnerTrace
		/*
		 * We don't know whether null is a reasonable fallback. Blocking exception is always correct.
		 * Application can call initial() to change this.
		 */
		.of(new ReactiveVariable<>(new ReactiveValue<T>(new ReactiveBlockingException(), true))
			/*
			 * Since the worker is based on "dameon" reactive thread, it would be garbage collected if it is not directly referenced.
			 * It can be however referenced indirectly by dependent computations waiting on the variable.
			 * So link the variable to the worker to avoid premature garbage collection.
			 */
			.keepalive(this)
			/*
			 * Disable equality in the variable, because we are already implementing it ourselves.
			 */
			.equality(false))
		.parent(this)
		.tag("role", "output")
		.target();
	/*
	 * Equality is configurable just like in reactive variable.
	 * We don't forward it to the reactive variable, because we have to do equality checks ourselves.
	 */
	private boolean equality = true;
	public synchronized ReactiveWorker<T> equality(boolean equality) {
		ensureNotStarted();
		this.equality = equality;
		return this;
	}
	public synchronized boolean equality() {
		return equality;
	}
	/*
	 * Since blocking exception is the default, the only other common alternative is blocking fallback.
	 * If none of these two options work, application can set arbitrary initial reactive value.
	 */
	public synchronized ReactiveWorker<T> initial(ReactiveValue<T> value) {
		ensureNotStarted();
		output.value(value);
		return this;
	}
	public ReactiveWorker<T> initial(T result) {
		return initial(new ReactiveValue<>(result, true));
	}
	/*
	 * Worker should cease to work when its output is not used. We will track usage with a flag.
	 */
	private final ReactiveVariable<Boolean> used = OwnerTrace
		.of(new ReactiveVariable<>(false))
		.parent(this)
		.tag("role", "used")
		.target();
	/*
	 * We are supposed to cease activity when the worker is unused, but how do we know it is unused?
	 * When the output changes, we will receive a get() call as dependent reactive computations are reevaluated.
	 * But what if the output doesn't change, i.e. it compares equal? We cannot invalidate dependents on equal output
	 * and since the dependents that last called get() might no longer exist or they could be garbage,
	 * we might end up producing a long train of equal outputs unnecessarily.
	 * 
	 * We will therefore produce probing invalidations that are exponentially spaced in time.
	 * Time here is measured in number of state machine advancements we have made without changing the output.
	 * 
	 * Age below is the number of outputs that compared equal. It can be non-reactive.
	 * Generation is incremented when the highest bit of age moves up. It is reactive as it is used to signal dependents.
	 */
	private long age;
	private final ReactiveVariable<Integer> generation = OwnerTrace
		.of(new ReactiveVariable<>(-1)
			/*
			 * This variable is accessed from get(). Make sure that reference to it will keep the whole worker alive.
			 * This is not strictly necessary since access to this variable is coupled with access to output variable,
			 * but we want to be sure.
			 */
			.keepalive(this))
		.parent(this)
		.tag("role", "generation")
		.target();
	/*
	 * We will use reactive thread and state machine instead of reactive primitives (scope, trigger, pins).
	 * This is lazy, costing us some performance. In the future, we might want to use the primitives instead.
	 */
	private final ReactiveStateMachine<T> generator = OwnerTrace.of(ReactiveStateMachine.supply(this::supply))
		.parent(this)
		.target();
	/*
	 * We don't have to synchronize anything, because reactive variables used here are already synchronized
	 * and we never rely on simultaneous changes to multiple variables. Some variables are also used exclusively here.
	 */
	private void run() {
		ReactiveValue<T> last;
		/*
		 * Don't ever do anything if we already use the last output from the state machine.
		 */
		if (generator.valid())
			return;
		last = output.value();
		/*
		 * Cease all activity when nobody is using worker's output.
		 */
		if (!used.get()) {
			/*
			 * Since we cease activity despite state machine invalidation, the output is now stale. We will mark it as blocking.
			 * Blocking output can be left stale, because we will get an opportunity to refresh it when someone requests it.
			 */
			if (!last.blocking())
				output.value(new ReactiveValue<>(last.result(), last.exception(), true));
			return;
		}
		generator.advance();
		ReactiveValue<T> fresh = generator.output();
		/*
		 * Accept blocking output only if the last output was blocking too.
		 * Once non-blocking output is produced, we don't want to ever revert to blocking output.
		 * We will just keep advancing the state machine until we reach the next non-blocking output.
		 */
		if (fresh.blocking() && !last.blocking())
			return;
		/*
		 * Don't do anything if advancement of the state machine had no real effect on its output.
		 * Equality logic is copied from reactive variable.
		 */
		if (!(equality ? fresh.equals(last) : fresh.same(last))) {
			/*
			 * Output is definitely changing. Worker should be considered unused until the next get() call.
			 */
			used.set(false);
			output.value(fresh);
		} else {
			/*
			 * Nothing changed, but we consumed some CPU time. This could be a problem if the worker is in fact already unreachable.
			 * Send probing invalidations time to time to ensure we are not doing this unnecessarily.
			 * 
			 * We only need to increment this when the output compares equal.
			 * Unequal output already invalidates all dependent reactive computations.
			 */
			++age;
			/*
			 * This will increment generation every time worker's age doubles (and also set it after first equality check).
			 * When incremented, probing invalidation is sent to all dependent reactive computations
			 * to see whether any of them are still alive.
			 */
			generation.set(Long.numberOfLeadingZeros(age));
		}
	}
	private final ReactiveThread thread = OwnerTrace
		.of(new ReactiveThread(this::run)
			/*
			 * Always a "daemon" reactive thread. Worker should be garbage collected when nobody reads its output.
			 */
			.daemon(true))
		.parent(this)
		.target();
	/*
	 * We will not expose the thread, because it's an implementation detail. We will just forward executor setting to it.
	 */
	public ReactiveWorker<T> executor(Executor executor) {
		thread.executor(executor);
		return this;
	}
	public Executor executor() {
		return thread.executor();
	}
	/*
	 * Synchronization is only needed to access and change 'started' variable.
	 */
	@Override public synchronized T get() {
		/*
		 * Start the reactive thread on first access.
		 */
		if (!started) {
			started = true;
			thread.start();
		}
		/*
		 * Someone requested the output. The worker is now considered used until it is invalidated.
		 */
		used.set(true);
		/*
		 * Create dependency on generation variable to allow the worker to send probing invalidations to all dependent computations.
		 */
		generation.get();
		return output.get();
	}
	@Override public String toString() {
		return OwnerTrace.of(this) + " = " + output.value();
	}
}