// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.micrometer.core.instrument.*;

/*
 * Latency-optimized executor designed for hookless. Currently it's just standard ThreadPoolExecutor with custom queue.
 *
 * Reactive executor has an event concept. Event is a group of related tasks that likely originated from single UI event.
 * When event's task schedules another task (e.g. due to reactive invalidation), the new task becomes part of the same event.
 * On the other hand, when task is scheduled from outside of the thread pool, it is assumed to be part of the next event.
 * 
 * When not under load, not even intermittently, latency is low regardless of executor.
 * This executor is designed to provide good latencies even under load.
 * The problem it is solving is that the usual FIFO queuing causes cascading tasks to have latency that is a multiple of the FIFO queue length.
 * This is a problem for hookless, which encourages construction of deep networks of reactive computations.
 * LIFO queuing is not a solution either as it has exponential complexity in the worst case.
 * Reactive executor solves the problem by keeping one global FIFO queue of events that each have their own local FIFO queue of tasks.
 * This has the effect that cascading tasks inside one event will all run together, yielding short latencies independent of cascading depth.
 * There is a reasonable limit on cascading depth to prevent buggy code from creating events that never stop.
 */
public class ReactiveExecutor extends ThreadPoolExecutor {
	private static ThreadLocal<ReactiveTask> running = new ThreadLocal<>();
	/*
	 * Event and task FIFOs do not physically exist. We just assign increasing event and task IDs to all tasks
	 * and then use priority queue to execute tasks in event order followed by task order.
	 * Event counter is executor-local, so that slow thread pools do not impede progress in fast thread pools.
	 */
	private final AtomicLong eventCounter = new AtomicLong();
	private static final AtomicLong taskCounter = new AtomicLong();
	/*
	 * Expose event counter, so that non-default reactive thread pools can be monitored.
	 * There's no need to expose task counter in the same way,
	 * because ThreadPoolExecutor already exposes getTaskCount() that returns about the same number.
	 */
	public long getEventCount() {
		return eventCounter.get();
	}
	/*
	 * We will wrap every task submitted to the executor in order to make the tasks sortable by event/task ID.
	 */
	private class ReactiveTask implements Runnable, Comparable<ReactiveTask> {
		final long eventId;
		final long taskId = taskCounter.incrementAndGet();
		/*
		 * This is cascade depth. Child tasks have depth one higher than their parent task.
		 */
		final int depth;
		final Runnable runnable;
		ReactiveTask(long eventId, int depth, Runnable runnable) {
			this.eventId = eventId;
			this.runnable = runnable;
			this.depth = depth;
		}
		boolean ownedBy(ReactiveExecutor executor) {
			return executor == ReactiveExecutor.this;
		}
		@Override public int compareTo(ReactiveTask other) {
			if (eventId != other.eventId)
				return eventId < other.eventId ? -1 : 1;
			else
				return Long.compare(taskId, other.taskId);
		}
		@Override public void run() {
			/*
			 * While incrementing task ID is straightforward, we have several options as to when to increment event ID.
			 * Here we increment it when the first task of the current event starts execution.
			 * This way we will typically have only two events: current one and the next one.
			 * Multi-threaded execution can however cause some older events to be still finishing their tasks.
			 * 
			 * Incrementing event ID in this way makes it likely that a train of consecutive task submissions will share one event ID.
			 * Related tasks that are likely coming from the same UI interaction will form single event in the executor.
			 * 
			 * When the executor is overloaded, multiple UI-level events get aggregated under the next event ID.
			 * That prevents buildup of a queue of tiny events that would each expand into a large tree of tasks.
			 * This kind of inefficiency is also present when we have only two events (current and the next one),
			 * but its impact on throughput is tolerable when there are only two active events.
			 * Normal FIFO queue of ThreadPoolExecutor would eliminate all such inefficiency,
			 * but reactive executor is designed to reduce latency and we are willing to sacrifice some throughput for it.  
			 */
			eventCounter.compareAndSet(eventId, eventId + 1);
			/*
			 * Expose reference to the current task via thread-local variable, so that child tasks spawned from this task inherit event ID.
			 */
			running.set(this);
			try {
				runnable.run();
			} finally {
				/*
				 * Remove. Do not set to null as that might result in accumulation of state when threads are stopped and started in the pool.
				 */
				running.remove();
			}
		}
	}
	public ReactiveExecutor(int min, int max, long timeout, TimeUnit unit, ThreadFactory threads) {
		super(min, max, timeout, unit, new PriorityBlockingQueue<>(), threads);
	}
	public ReactiveExecutor(int min, int max, long timeout, TimeUnit unit) {
		this(min, max, timeout, unit, Executors.defaultThreadFactory());
	}
	/*
	 * We have to choose maximum cascading depth to prevent infinite cascades (busy-looping reactive code)
	 * from creating infinite events that would live-lock all other reactive computations.
	 * Almost all task cascades are less than 10 tasks deep. If we set the limit to 30, it will be sufficient with wide margin.
	 * Even if the cascade is longer, tasks will still be aggregated in groups of 30, reducing total latency by a factor of 30.
	 */
	private static final int MAX_DEPTH = 30;
	@Override public void execute(Runnable runnable) {
		Objects.requireNonNull(runnable);
		ReactiveTask current = running.get();
		/*
		 * We will check whether the current task belongs to this executor, because every executor has separate event counter.
		 */
		if (current != null && current.ownedBy(this) && current.depth < MAX_DEPTH)
			super.execute(new ReactiveTask(current.eventId, current.depth + 1, runnable));
		else
			super.execute(new ReactiveTask(eventCounter.get(), 0, runnable));
	}
	/*
	 * We will define one common reactive executor that will be used as default in all reactive primitives that need an executor.
	 * This executor will be compute-optimized with thread count equal to core count. Submitting blocking operations here will undermine performance.
	 * Minimum thread count is zero, so that this thread pool consumes no resources when not in use.
	 */
	private static final ReactiveExecutor common = new ReactiveExecutor(0, Runtime.getRuntime().availableProcessors(), 1, TimeUnit.MINUTES, new ThreadFactory() {
		@Override public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable);
			/*
			 * Do not block process termination just because some reactive computations are still running in the background.
			 */
			thread.setDaemon(true);
			thread.setName("hookless-" + thread.getId());
			return thread;
		}
	});
	static {
		Metrics.gauge("hookless.executor.events", common, x -> x.getEventCount());
		Metrics.gauge("hookless.executor.tasks", common, x -> x.getTaskCount());
		Metrics.gauge("hookless.executor.threads", common, x -> x.getPoolSize());
		Metrics.gauge("hookless.executor.queue", common, x -> x.getQueue().size());
	}
	public static ReactiveExecutor common() {
		return common;
	}
}
