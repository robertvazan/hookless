package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import com.machinezoo.noexception.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

/*
 * Java's Thread cannot have direct reactive wrapper, because its Runnable is by nature blocking.
 * Reactive thread is instead only a conceptual equivalent of Java Thread.
 * It takes a Runnable that has no return value and instead acts through side-effects of its execution.
 * While Java Thread runs a blocking Runnable once, reactive thread runs Runnable whenever its dependencies change.
 * 
 * This difference in behavior unfortunately leads to the problem of how to terminate the reactive thread.
 * Java Thread terminates simply when its Runnable terminates, but that's not an option for reactive threads.
 * Reactive thread certainly terminates when non-blocking exception is caught, but exceptions cannot be used for regular termination.
 * Reactive thread always keeps running when the Runnable reactively blocks, but we cannot require thread bodies to block forever.
 * We will instead keep calling the Runnable until stop() is called. Threads can self-terminate by calling current() and then stop().
 * 
 * API details are kept consistent with the other reactive classes rather than with Java's Thread.
 * That's why method names are slightly different and some methods are fluent.
 */
public class ReactiveThread {
	/*
	 * The thread can be in three states: initialized, running, and stopped. Initialized state allows changes to configuration.
	 */
	private boolean started;
	private boolean stopped;
	private void ensureNotStarted() {
		if (started)
			throw new IllegalStateException();
	}
	private Runnable runnable;
	/*
	 * This method has no equivalent in Java Thread, but our fluent API works better with it.
	 */
	public synchronized ReactiveThread runnable(Runnable runnable) {
		Objects.requireNonNull(runnable);
		ensureNotStarted();
		this.runnable = runnable;
		return this;
	}
	public synchronized Runnable runnable() {
		return runnable;
	}
	public ReactiveThread() {
		OwnerTrace.of(this).alias("thread");
		runnable = () -> {
		};
	}
	public ReactiveThread(Runnable runnable) {
		/*
		 * Make sure OwnerTrace is set up.
		 */
		this();
		Objects.requireNonNull(runnable);
		this.runnable = runnable;
	}
	protected void run() {
		runnable.run();
	}
	/*
	 * Since reactive thread is not really a thread but rather a fiber, it needs an actual thread to run on.
	 * We allow configuration of executor, so that heavy reactive threads can be kept off the main reactive executor.
	 */
	private Executor executor = ReactiveExecutor.instance();
	public synchronized ReactiveThread executor(Executor executor) {
		Objects.requireNonNull(executor);
		ensureNotStarted();
		this.executor = executor;
		return this;
	}
	public synchronized Executor executor() {
		return executor;
	}
	/*
	 * While most reactive objects are garbage-collected automatically, GCing running reactive threads would be counterintuitive.
	 * We will therefore keep all running reactive threads reachable even if the application doesn't bother to keep a reference to them.
	 * This is consistent with behavior of Java Thread except that all reactive threads are GCed when this class is unloaded.
	 * There are however times when the application needs to create object-local reactive threads that should be GCed with the object.
	 * Reactive thread can be optionally configured in "daemon" mode that allows garbage collection of running reactive threads.
	 */
	private static final Set<ReactiveThread> running =
		Metrics.gaugeCollectionSize("hookless.thread.running", Collections.emptyList(), ConcurrentHashMap.newKeySet());
	private boolean daemon;
	public synchronized ReactiveThread daemon(boolean daemon) {
		ensureNotStarted();
		this.daemon = daemon;
		return this;
	}
	public synchronized boolean daemon() {
		return daemon;
	}
	/*
	 * Java Thread has several methods for monitoring thread state. We can create their reactive equivalents.
	 * TODO: Add monitoring methods when there's time and need. Use reactive variable, possibly lazily initialized.
	 * 
	 * Supportable state monitoring methods (all reactive):
	 * - getState()
	 * - isAlive()
	 * - join()
	 *
	 * Supportable thread states:
	 * - NEW
	 * - RUNNABLE - when new iteration is scheduled or already running
	 * - BLOCKED - when waiting on reactive trigger and last value is blocking
	 * - WAITING - when waiting on reactive trigger and last value is not blocking
	 * - TERMINATED
	 * 
	 * Unsupportable APIs of Java Thread:
	 * - join(long)
	 * - join(long, int)
	 * - Thread.State.TIMED_WAITING
	 * 
	 * It is tempting to provide this functionality using futures (completable or reactive),
	 * but that is hopelessly buggy for daemon reactive threads (that might leave futures uncompleted when GCed),
	 * unnecessarily expands features beyond Java's Thread, and it duplicates functionality from reactive futures.
	 * 
	 * We will not create equivalents of enumeration methods from Java Thread, but we will offer current() method,
	 * which is indispensable, because it allows calling stop() on the current thread without holding a reference to it.
	 */
	private static final ThreadLocal<ReactiveThread> current = new ThreadLocal<>();
	public static ReactiveThread current() {
		return current.get();
	}
	/*
	 * Reactive thread is implemented using reactive scope and trigger with support for pinning.
	 */
	private ReactiveTrigger trigger;
	private ReactivePins pins;
	/*
	 * Timer sample is kept on instance level, so that we can also capture latency,
	 * i.e. how long it took for the thread's runnable to be scheduled for execution.
	 */
	private Timer.Sample sample;
	private static final Timer timer = Metrics.timer("hookless.thread.iterations");
	/*
	 * Suppress resource warnings caused by closeable trigger not being closed after being constructed.
	 */
	@SuppressWarnings("resource") private void iterate() {
		Timer.Sample sample;
		ReactiveScope scope;
		synchronized (this) {
			/*
			 * In case stop() was called while we were waiting in executor queue.
			 */
			if (stopped)
				return;
			sample = this.sample;
			this.sample = null;
			scope = OwnerTrace.of(new ReactiveScope())
				.parent(this)
				.target();
			if (pins != null)
				scope.pins(pins);
			pins = null;
		}
		try (ReactiveScope.Computation computation = scope.enter()) {
			try {
				try {
					current.set(this);
					run();
				} finally {
					current.remove();
					sample.stop(timer);
				}
			} catch (Throwable ex) {
				/*
				 * Silently ignore exceptions when the reactive computation is blocking, because they are normal.
				 */
				if (!scope.blocked()) {
					/*
					 * Nothing to do with the exception, so at least log it.
					 */
					Exceptions.log().handle(ex);
					synchronized (this) {
						stopped = true;
					}
					running.remove(this);
					return;
				}
			}
		}
		synchronized (this) {
			/*
			 * In case stop() was called while the runnable was running.
			 */
			if (stopped)
				return;
			if (scope.blocked())
				pins = scope.pins();
			trigger = OwnerTrace
				.of(new ReactiveTrigger()
					.callback(this::invalidate))
				.parent(this)
				.target();
			trigger.arm(scope.versions());
		}
	}
	private void schedule() {
		/*
		 * Include scheduling latency in execution time. Latency is what we care about in UIs.
		 */
		sample = Timer.start(Clock.SYSTEM);
		/*
		 * Method iterate() should never throw, but let's make sure.
		 * 
		 * This temporarily creates a strong reference to this reactive thread, which can prevent it from being GCed.
		 * But since reactive thread should be normally cold (rarely firing) and executors should have low latencies (for interactive UIs),
		 * it is very unlikely that reactive thread will not be GCed on time. Running iterate() method has the same effect.
		 * If the reactive thread is intended to be hot (running all the time), application code should stop it explicitly.
		 * TODO: In the future, we might want to use weak references at least for the queued reactive threads,
		 * which would limit GC interference to the currently executing reactive threads, which are limited by thread pool size.
		 */
		executor.execute(Exceptions.log().runnable(this::iterate));
	}
	private synchronized void invalidate() {
		/*
		 * We could receive invalidation callback after stop() was called.
		 * In that case the trigger was already destroyed and we have nothing to do here.
		 */
		if (stopped)
			return;
		trigger.close();
		schedule();
	}
	public synchronized ReactiveThread start() {
		/*
		 * It is allowed to start the thread twice. The second call has no effect.
		 */
		if (started)
			return this;
		started = true;
		/*
		 * It is allowed to stop the thread before it is started. In that case we don't run even a single iteration.
		 */
		if (stopped)
			return this;
		if (!daemon)
			running.add(this);
		schedule();
		return this;
	}
	public synchronized void stop() {
		/*
		 * All of the code below has to tolerate double stop() calls.
		 */
		stopped = true;
		running.remove(this);
		/*
		 * Eagerly clean up all the reactive resources.
		 * This is useful when the reactive thread object lingers in the object graph after it is closed.
		 */
		if (trigger != null) {
			trigger.close();
			trigger = null;
		}
		sample = null;
		pins = null;
	}
	@Override public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
