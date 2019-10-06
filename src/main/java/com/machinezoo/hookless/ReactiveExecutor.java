// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.concurrent.*;
import java.util.concurrent.ForkJoinPool.*;
import io.micrometer.core.instrument.*;

public class ReactiveExecutor {
	private static final ExecutorService instance;
	public static ExecutorService instance() {
		return instance;
	}
	static {
		ForkJoinPool executor = new ForkJoinPool(Runtime.getRuntime().availableProcessors(), new ForkJoinWorkerThreadFactory() {
			@Override public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
				return new ForkJoinWorkerThread(pool) {
					@Override protected void onStart() {
						super.onStart();
						setName(ReactiveExecutor.class.getSimpleName() + "-" + getId());
					}
				};
			}
		}, null, true);
		Metrics.gauge("hookless.executor.latency", executor, e -> latency());
		Metrics.gauge("hookless.executor.threads", executor, ForkJoinPool::getPoolSize);
		Metrics.gauge("hookless.executor.threads.active", executor, ForkJoinPool::getActiveThreadCount);
		Metrics.gauge("hookless.executor.threads.running", executor, ForkJoinPool::getRunningThreadCount);
		Metrics.gauge("hookless.executor.tasks.submitted", executor, ForkJoinPool::getQueuedSubmissionCount);
		Metrics.gauge("hookless.executor.tasks.queued", executor, ForkJoinPool::getQueuedTaskCount);
		instance = executor;
	}
	private static boolean pending;
	private static long start;
	private static double latency;
	private static synchronized double latency() {
		if (!pending) {
			pending = true;
			start = System.nanoTime();
			instance.execute(() -> {
				synchronized (ReactiveExecutor.class) {
					pending = false;
					latency = (System.nanoTime() - start) * 0.000_000_001;
				}
			});
			return latency;
		} else
			return (System.nanoTime() - start) * 0.000_000_001;
	}
}
