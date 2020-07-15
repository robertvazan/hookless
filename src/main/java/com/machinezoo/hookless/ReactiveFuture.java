// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.lang.ref.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.google.common.util.concurrent.*;
import com.machinezoo.hookless.time.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.stagean.*;

/*
 * Reactive wrapper around CompletableFuture.
 */
/**
 * Reactive wrapper for {@link CompletableFuture}.
 * 
 * @param <T>
 *            type of result returned by the future
 */
@StubDocs
public class ReactiveFuture<T> {
	/*
	 * Strong reference to CompletableFuture. If something references us, CompletableFuture must stay alive.
	 */
	private final CompletableFuture<T> completable;
	public CompletableFuture<T> completable() {
		return completable;
	}
	/*
	 * Reactive future works by automatically transferring CompletableFuture state to reactive variable.
	 * CompletableFuture states are a subset of reactive values (including blocking), which makes this very simple.
	 * 
	 * There's no need to keepalive() the reactive future, because completion callback
	 * keeps the reactive future alive for as long as is necessary to ensure reactivity.
	 * And CompletableFuture always stays alive long enough to invoke all chained actions.
	 */
	private final ReactiveVariable<T> variable = OwnerTrace
		.of(new ReactiveVariable<T>(new ReactiveValue<T>(null, null, true)))
		.parent(this)
		.target();
	/*
	 * This is a separate method to make absolutely sure the completion callback created in constructor
	 * will hold strong reference to 'this' (the reactive future) rather than just to the embedded reactive variable.
	 * Two-way strong reference between reactive future and CompletableFuture ties lifetimes of the two objects,
	 * which is essential to make deduplication of reactive future instances work in the WeakHashMap below.
	 */
	private void complete(T result, Throwable exception) {
		variable.value(new ReactiveValue<>(result, exception, false));
	}
	private ReactiveFuture(CompletableFuture<T> completable) {
		OwnerTrace.of(this).alias("future");
		Objects.requireNonNull(completable);
		this.completable = completable;
		OwnerTrace.of(completable).parent(this);
		/*
		 * If the future is already completed, the callback is invoked before whenComplete() returns.
		 * If it is not completed yet, callback is invoked synchronously.
		 * That means there's no latency difference between CompletableFuture and its wrapping reactive future.
		 */
		completable.whenComplete(this::complete);
	}
	/*
	 * Give users freedom to choose whether to create CompletableFuture first or reactive future first.
	 */
	public ReactiveFuture() {
		this(new CompletableFuture<>());
		synchronized (ReactiveFuture.class) {
			associations.put(completable, new WeakReference<>(this));
		}
	}
	/*
	 * Creating new reactive future adds a completion handler to the wrapped CompletableFuture.
	 * If we allowed multiple reactive futures for one CompletableFuture, these completion handlers would pile up.
	 * We will therefore enforce single wrapper per CompletableFuture. This is also more convenient to use.
	 * 
	 * WeakHashMap essentially adds a dynamic field to CompletableFuture that references the associated reactive future.
	 * The value of WeakHashMap however cannot be a strong reference, because WeakHashMap causes memory leaks
	 * when value (reactive future) holds strong reference to its key (CompletableFuture), which is our case.
	 * Only ephemerons can be used in such scenario, but those cannot be implemented in Java.
	 * See:
	 * https://en.wikipedia.org/wiki/Ephemeron
	 * https://stackoverflow.com/a/9166730
	 * 
	 * So we either remove strong back-reference to CompletableFuture, make it weak, or make WeakHashMap values weak.
	 * We cannot remove reference to CompletableFuture, because we offer API where reactive future is constructed first
	 * and then CompletableFuture is retrieved from it. We cannot make the CompletableFuture reference weak,
	 * because callers expect the CompletableFuture to exist as long as they hold a reference to its associated reactive future.
	 * So the only remaining option is to make WeakHashMap values weak.
	 * 
	 * Reactive future is held alive by completion callback from reachable CompletableFuture (see complete() above).
	 * Completion callback exists at least for as long as the CompletableFuture is not completed.
	 * Reactive future therefore becomes unreachable when (1) it is not referenced directly
	 * and (2) its associated CompletableFuture is either unreachable or already completed.
	 * If the CompletableFuture is unreachable, then there is no one to complete it and reactivity is irrelevant.
	 * It the CompletableFuture is already completed, no state change can happen anymore and reactivity is again irrelevant.
	 * It is therefore safe for reactive future to be collected under these circumstances.
	 * Its reactive variable might live longer if it is a dependency of some reactive computation, which is harmless.
	 * 
	 * These rules however allow reactive future to be collected while its (completed) CompletableFuture is still reachable.
	 * That is okay, because the completed CompletableFuture no longer holds the completion callback
	 * (as otherwise the reactive future would not be collected), so creating new reactive future for the CompletableFuture
	 * will not cause accumulation of the completion callbacks that we were trying to prevent with this.
	 * Callers would not ever observe two instances of reactive future for single CompletableFuture,
	 * because the first reactive future is collected only after there are no references to it,
	 * so callers cannot compare the second reactive future to anything they know.
	 */
	private static final Map<CompletableFuture<?>, WeakReference<ReactiveFuture<?>>> associations = new WeakHashMap<>();
	@SuppressWarnings("unchecked")
	public static synchronized <T> ReactiveFuture<T> wrap(CompletableFuture<T> completable) {
		Objects.requireNonNull(completable);
		WeakReference<ReactiveFuture<?>> weak = associations.get(completable);
		ReactiveFuture<?> cached = weak != null ? weak.get() : null;
		if (cached != null)
			return (ReactiveFuture<T>)cached;
		ReactiveFuture<T> reactive = new ReactiveFuture<T>(completable);
		associations.put(completable, new WeakReference<>(reactive));
		return reactive;
	}
	/*
	 * We can now expose reactive variants of CompletableFuture methods.
	 * Only read methods are provided. Writes and continuations can be done via the associated CompletableFuture.
	 * Method names have been adapted to fit hookless style.
	 */
	public boolean done() {
		return !variable.value().blocking();
	}
	public boolean failed() {
		return variable.value().exception() != null;
	}
	public boolean cancelled() {
		return variable.value().exception() instanceof CancellationException;
	}
	/*
	 * There is no join() method, because that one is just a workaround for checked exceptions thrown by CompletableFuture's get().
	 * We do the right thing from the beginning and consistently throw CompletionException instead of ExecutionException.
	 * 
	 * These methods don't need to be synchronized, because reactive variable is already synchronized.
	 */
	private T unpack(ReactiveValue<T> value) {
		if (value.exception() instanceof CancellationException)
			throw new CancellationException();
		if (value.exception() != null)
			throw new CompletionException(value.exception());
		return value.result();
	}
	public T get() {
		ReactiveValue<T> value = variable.value();
		if (value.blocking()) {
			/*
			 * Propagate blocking just like in CompletableFuture.
			 * We don't have any fallback value and it might not be correct to fallback to null, so just throw.
			 */
			throw ReactiveBlockingException.block();
		}
		return unpack(value);
	}
	public T getNow(T fallback) {
		ReactiveValue<T> value = variable.value();
		if (value.blocking()) {
			/*
			 * Don't propagate blocking. This method is specifically intended to avoid all blocking.
			 * CompletableFuture does not block (synchronously) either.
			 */
			return fallback;
		}
		return unpack(value);
	}
	/*
	 * Timeout should be counted from the first call to the timeouting overload in order to avoid cascading of timeouts.
	 * Implementing timeouts via reactive pins would never work reliably if at all.
	 */
	private Instant start;
	/*
	 * CompletableFuture uses the legacy TimeUnit enum. This is a new API, so use the new Duration class instead.
	 * This is the only synchronized get* method due to the timestamp field access.
	 */
	public synchronized T get(Duration timeout) {
		Objects.requireNonNull(timeout);
		ReactiveValue<T> value = variable.value();
		/*
		 * First check whether we have value available even if the timeout has been already reached.
		 * This results in somewhat odd behavior when the future first throws due to timeout and later returns the actual result.
		 * But then this is reactive API and function results are expected to change over time.
		 */
		if (value.blocking()) {
			/*
			 * This is a rarely used feature. Initialize timestamp lazily to avoid burdening the typical case.
			 * This also lets us count time from the first moment this method was called rather than since object creation.
			 * That works better when this future is precreated and then lies around for some time.
			 */
			if (start == null)
				start = Instant.now();
			if (ReactiveInstant.now().isAfter(start.plus(timeout))) {
				/*
				 * Do not reactively block. The whole point of the timeout is to limit the duration of blocking.
				 * CompletableFuture does not block (synchronously) in this case either.
				 * 
				 * Throw unchecked variant of TimeoutException to simplify use of the API and to stay consistent with other hookless APIs.
				 * This exception type comes from Guava, which means we are creating hard dependency on Guava.
				 * That's somewhat controversial, but it's better than declaring our own or throwing checked exceptions.
				 */
				throw new UncheckedTimeoutException();
			}
			/*
			 * If timeout has not been reached yet, continue like in get().
			 */
			throw ReactiveBlockingException.block();
		}
		return unpack(value);
	}
	/*
	 * Offer the TimeUnit-based API as well for compatibility with CompletableFuture.
	 */
	public T get(long timeout, TimeUnit unit) {
		/*
		 * Java 9 has TimeUnit.toChronoUnit(), which could be then used with Duration.of().
		 * In Java 8, roundtrip via nanoseconds will suffice for timeouts up to 292 years.
		 */
		return get(Duration.ofNanos(unit.toNanos(timeout)));
	}
	@Override
	public String toString() {
		ReactiveValue<T> value = variable.value();
		if (value.blocking())
			CurrentReactiveScope.block();
		return OwnerTrace.of(this) + " = " + (value.blocking() ? "(pending)" : Objects.toString(value.exception(), Objects.toString(value.result())));
	}
	/*
	 * The following methods are equivalents of CompletableFuture's run/supplyAsync methods.
	 * Provided reactive supplier/runnable is executed repeatedly until it completes without blocking.
	 * 
	 * These methods serve as a bridge from reactive computations to the async world of CompletableFuture,
	 * which is why they return CompletableFuture instead of reactive future.
	 */
	public static <T> CompletableFuture<T> supplyReactive(Supplier<T> supplier, Executor executor) {
		Objects.requireNonNull(supplier);
		Objects.requireNonNull(executor);
		CompletableFuture<T> future = new CompletableFuture<T>();
		/*
		 * We have to return only CompletableFuture, but it is convenient to have reactive future too.
		 * We can then avoid complicated callbacks needed just to implement cancellation.
		 */
		ReactiveFuture<T> reactive = wrap(future);
		/*
		 * The reactive thread is configured to run in non-daemon mode, so that it keeps running until the CompletableFuture is completed.
		 * This reflects the way corresponding run/supplyAsync methods in CompletableFuture work.
		 * This is not much of an issue, because the reactive computations are typically very short.
		 * There is unfortunately still a small risk that these reactive computations will block forever.
		 * If that is a concern for the application, it should wrap the supplier/runnable with custom timeout check.
		 */
		ReactiveThread thread = new ReactiveThread()
			.runnable(() -> {
				/*
				 * Allow explicit cancellation. CompletableFuture also supports this feature but only before the supplier is started.
				 * Since the reactive supplier is executed multiple times, it is possible to cancel it when already running.
				 * This is a little bit incorrect, because we are ignoring the flag that was passed to future's cancel() method.
				 * Checking state of reactive future instead of the CompletableFuture speeds up termination and release of resources.
				 * We also allow cancellation by normal or exceptional completion of the future just like run/supplyAsync does.
				 */
				if (reactive.done()) {
					ReactiveThread.current().stop();
					return;
				}
				ReactiveValue<T> value = ReactiveValue.capture(supplier);
				if (!CurrentReactiveScope.blocked()) {
					if (value.exception() != null)
						future.completeExceptionally(value.exception());
					else
						future.complete(value.result());
					ReactiveThread.current().stop();
				}
			})
			.executor(executor);
		OwnerTrace.of(thread).parent(future);
		thread.start();
		return future;
	}
	public static <T> CompletableFuture<T> supplyReactive(Supplier<T> supplier) {
		return supplyReactive(supplier, ReactiveExecutor.common());
	}
	public static CompletableFuture<Void> runReactive(Runnable runnable, Executor executor) {
		Objects.requireNonNull(runnable);
		return supplyReactive(() -> {
			runnable.run();
			return null;
		}, executor);
	}
	public static CompletableFuture<Void> runReactive(Runnable runnable) {
		return runReactive(runnable, ReactiveExecutor.common());
	}
}
