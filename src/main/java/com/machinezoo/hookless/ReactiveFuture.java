package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import com.machinezoo.noexception.*;

public class ReactiveFuture<T> {
	private final CompletableFuture<T> completable;
	public CompletableFuture<T> completable() {
		return completable;
	}
	private ReactiveFuture(CompletableFuture<T> completable) {
		Objects.requireNonNull(completable);
		this.completable = completable;
	}
	private final ReactiveVariable<T> state = OwnerTrace.of(new ReactiveVariable<T>(new ReactiveValue<T>(null, null, true)))
		.parent(OwnerTrace.of(this).alias("future"))
		.target();
	private static final Map<CompletableFuture<?>, ReactiveFuture<?>> associations = new WeakHashMap<>();
	@SuppressWarnings("unchecked") public static <T> ReactiveFuture<T> wrap(CompletableFuture<T> completable) {
		if (completable.isDone()) {
			ReactiveFuture<T> reactive = new ReactiveFuture<T>(completable);
			T result = null;
			Throwable exception = null;
			try {
				result = completable.get();
			} catch (CancellationException e) {
				exception = e;
			} catch (ExecutionException e) {
				exception = e.getCause();
			} catch (InterruptedException e) {
				Exceptions.sneak().handle(e);
			}
			reactive.state.value(new ReactiveValue<>(result, exception, false));
			return reactive;
		} else {
			ReactiveFuture<T> reactive;
			synchronized (associations) {
				ReactiveFuture<?> cached = associations.get(completable);
				if (cached != null)
					return (ReactiveFuture<T>)cached;
				associations.put(completable, reactive = new ReactiveFuture<T>(completable));
			}
			completable.whenComplete((result, exception) -> reactive.state.value(new ReactiveValue<>(result, exception, false)));
			return reactive;
		}
	}
	public synchronized T getNow(T fallback) {
		ReactiveValue<T> latest = state.value();
		if (latest.blocking())
			return fallback;
		if (latest.exception() != null)
			throw new CompletionException(latest.exception());
		return latest.result();
	}
	public synchronized boolean isCompletedExceptionally() {
		return state.value().exception() != null;
	}
	public synchronized boolean isDone() {
		return !state.value().blocking();
	}
	@Override public synchronized String toString() {
		ReactiveValue<T> latest = state.value();
		return OwnerTrace.of(this) + " = " + (latest.blocking() ? "(pending)" : Objects.toString(latest.exception(), Objects.toString(latest.result())));
	}
}
