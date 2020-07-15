// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.stagean.*;

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
/**
 * Single-value asynchronous cache for results of reactive computations.
 * 
 * @param <T>
 *            type of cached result
 */
@StubDocs
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
			 * Even though such dependent computations cannot retrieve new values since they don't have worker reference,
			 * they might still need to be informed about changes in their dependencies, which wouldn't happen if the worker is GC-ed.
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
	 * Using reactive thread in daemon mode ensures that unused reactive workers will not waste memory.
	 * Unused worker can however still waste CPU until it is GC-ed, which might happen after a very long time.
	 * We cannot directly check whether the worker is used, because dependent computations themselves could be garbage.
	 * We will instead produce probing invalidations time to time that wake up dependent computations.
	 * Dependents are then expected to reread worker's output, which is something we can detect.
	 * If no dependent computation reads worker's output, we will leave the worker in paused state.
	 * We cannot destroy the worker yet, because we could later get a read, which causes the worker to resume activity.
	 * 
	 * Now the question is what does "time to time" mean. Invalidations cause expensive reevaluation of dependent computations.
	 * We will space them exponentially in time to limit their cost. Time here means worker activity measured in number of iterations.
	 * 
	 * This strategy is less effective against deep graphs of reactive workers
	 * where worker longevity may grow exponentially with graph depth.
	 * Dealing with such situations is still possible, but it would require more frequent pausing, likely guided by real time.
	 * 
	 * Age below is the number of iterations, i.e. invocations of run() method. It doesn't need to be reactive.
	 * Generation is incremented when the highest bit of age moves up. This results in exponential spacing of invalidations.
	 * We will initialize age to non-zero value, so that a number of iterations is allowed to run before first pause.
	 */
	private long age = 4;
	private static int generation(long age) {
		/*
		 * Zero age results in generation 0. 1 -> 1, 2..3 -> 2, 4..7 -> 3, etc.
		 */
		return 64 - Long.numberOfLeadingZeros(age);
	}
	/*
	 * Current generation is written into this variable to ping all dependent computations.
	 */
	private final ReactiveVariable<Integer> ping = OwnerTrace
		.of(new ReactiveVariable<>(generation(age))
			/*
			 * This variable is accessed from get(). Make sure that reference to it will keep the whole worker alive.
			 * This is not strictly necessary since access to this variable is coupled with access to output variable,
			 * but we want to be sure.
			 */
			.keepalive(this))
		.parent(this)
		.tag("role", "ping")
		.target();
	/*
	 * Dependent computations acknowledge the ping by rereading worker's output.
	 * When get() is called, value of ping variable is copied into ack variable.
	 * This way the worker knows whether the last ping was acknowledged and thus whether it should be paused or not.
	 */
	private final ReactiveVariable<Integer> ack = OwnerTrace
		.of(new ReactiveVariable<>(generation(age)))
		.parent(this)
		.tag("role", "ack")
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
		/*
		 * Don't ever do anything if we already use the last output from the state machine.
		 */
		if (generator.valid())
			return;
		ReactiveValue<T> last;
		/*
		 * Synchronized to ensure correct state transitions for output+ping+ack trio.
		 */
		synchronized (this) {
			last = output.value();
			/*
			 * Cease all activity when nobody is using worker's output.
			 */
			boolean used = ping.get() == (int)ack.get();
			if (!used) {
				/*
				 * Since we cease activity despite state machine invalidation, the output is now stale. We will mark it as blocking.
				 * Blocking output can be left stale, because we will get an opportunity to refresh it when someone requests it.
				 */
				if (!last.blocking())
					output.value(new ReactiveValue<>(last.result(), last.exception(), true));
				return;
			}
		}
		/*
		 * Advancement of the generator as well as equality check are performed unsynchronized, because they can be slow.
		 */
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
		 * Equality logic is copied from reactive variable, but we have to guard against exceptions,
		 * because contrary to reactive variable, we cannot afford to propagate exceptions here.
		 */
		boolean equal;
		if (equality) {
			try {
				equal = fresh.equals(last);
			} catch (Throwable ex) {
				/*
				 * Assuming change is the safe thing to do in case application-defined equals() throws.
				 */
				equal = false;
			}
		} else
			equal = fresh.same(last);
		/*
		 * Synchronized to ensure correct state transitions for output+ping+ack trio.
		 */
		synchronized (this) {
			/*
			 * Don't change the output variable if advancement of the state machine had no real effect on the output.
			 */
			if (!equal)
				output.value(fresh);
			/*
			 * We have to prevent waste of CPU, which may occur if GC is slow to remove unused worker.
			 * Send probing invalidations time to time to check whether the worker is still used.
			 */
			++age;
			ping.set(generation(age));
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
	 * Synchronized to ensure correct state transitions for output+ping+ack trio.
	 */
	@Override
	public synchronized T get() {
		/*
		 * Start the reactive thread on first access.
		 */
		if (!started) {
			started = true;
			thread.start();
		}
		/*
		 * Someone requested the output. The worker is now considered used until next generation increment.
		 * This creates dependency on ping variable that will allow the worker to send probing invalidations to all dependent computations.
		 */
		ack.set(ping.get());
		return output.get();
	}
	@Override
	public String toString() {
		return OwnerTrace.of(this) + " = " + output.value();
	}
}
