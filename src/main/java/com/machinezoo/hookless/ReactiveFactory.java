// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.concurrent.*;
import java.util.function.*;

public class ReactiveFactory<T> {
	private final Supplier<T> supplier;
	public Supplier<T> supplier() {
		return supplier;
	}
	private final CompletableFuture<T> future = new CompletableFuture<>();
	private final ReactiveThread thread;
	public ReactiveFactory(Supplier<T> supplier) {
		this.supplier = supplier;
		OwnerTrace.of(this).alias("factory");
		thread = OwnerTrace
			.of(new ReactiveThread(() -> {
				T proposed;
				try {
					proposed = supplier.get();
				} catch (Throwable ex) {
					if (!CurrentReactiveScope.blocked()) {
						future.completeExceptionally(ex);
						ReactiveThread.current().stop();
					}
					return;
				}
				if (!CurrentReactiveScope.blocked()) {
					future.complete(proposed);
					ReactiveThread.current().stop();
				}
			}))
			.parent(this)
			.target();
	}
	private boolean started;
	public synchronized CompletableFuture<T> start() {
		if (!started) {
			if (supplier == null)
				throw new IllegalStateException("Specify supplier to run");
			thread.start();
		}
		return future();
	}
	public synchronized void cancel() {
		if (started) {
			thread.stop();
			future.completeExceptionally(new CancellationException());
		}
	}
	public CompletableFuture<T> future() {
		return future();
	}
	public ReactiveFactory<T> executor(ExecutorService executor) {
		thread.executor(executor);
		return this;
	}
	public ExecutorService executor() {
		return thread.executor();
	}
	@Override public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
