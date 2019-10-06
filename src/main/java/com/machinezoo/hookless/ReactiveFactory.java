// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.concurrent.*;
import java.util.function.*;

public class ReactiveFactory<T> {
	private final Supplier<T> supplier;
	public Supplier<T> supplier() {
		return supplier;
	}
	public ReactiveFactory(Supplier<T> supplier) {
		this.supplier = supplier;
	}
	private boolean started;
	private final ReactiveLoop<T> loop = OwnerTrace
		.of(new ReactiveLoop<T>() {
			@Override protected void run() {
				T proposed;
				try {
					proposed = supplier.get();
				} catch (Throwable e) {
					if (CurrentReactiveScope.blocked())
						return;
					throw e;
				}
				if (!CurrentReactiveScope.blocked())
					loop.complete(proposed);
			}
		})
		.parent(OwnerTrace.of(this).alias("factory"))
		.target();
	public synchronized CompletableFuture<T> start() {
		if (!started) {
			if (supplier == null)
				throw new IllegalStateException("Specify supplier to run");
			loop.start();
		}
		return future();
	}
	public synchronized void cancel() {
		if (started)
			loop.stop();
	}
	public CompletableFuture<T> future() {
		return loop.future();
	}
	public ReactiveFactory<T> executor(ExecutorService executor) {
		loop.executor(executor);
		return this;
	}
	public ExecutorService executor() {
		return loop.executor();
	}
	@Override public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
