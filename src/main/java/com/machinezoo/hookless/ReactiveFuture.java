// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.common.util.concurrent.*;
import com.machinezoo.hookless.time.*;
import com.machinezoo.hookless.utils.*;

/*
 * Reactive wrapper around CompletableFuture.
 */
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
	 * There's no need to keepalive() this object, because this is just a convenient API around the reactive variable.
	 * The variable is not written by this object. It is written directly by CompletableFuture.
	 * And CompletableFuture always stays alive long enough to invoke all chained actions.
	 */
	private final ReactiveVariable<T> variable = OwnerTrace
		.of(new ReactiveVariable<T>(new ReactiveValue<T>(null, null, true)))
		.parent(this)
		.target();
	private ReactiveFuture(CompletableFuture<T> completable) {
		OwnerTrace.of(this).alias("future");
		Objects.requireNonNull(completable);
		this.completable = completable;
		/*
		 * If the future is already completed, the callback is invoked before whenComplete() returns.
		 * If it is not completed yet, callback is invoked synchronously.
		 * That means there's no latency difference between CompletableFuture and its wrapping reactive future.
		 */
		completable.whenComplete((r, ex) -> variable.value(new ReactiveValue<>(r, ex, false)));
	}
	/*
	 * Give users freedom to choose whether to create CompletableFuture first or reactive future first.
	 */
	public ReactiveFuture() {
		this(new CompletableFuture<>());
	}
	/*
	 * Creating new reactive future adds a completion handler to the wrapped CompletableFuture.
	 * If we allowed multiple reactive futures for one CompletableFuture, these completion handlers would pile up.
	 * We will therefore enforce single wrapper per CompletableFuture. This is also more convenient to use.
	 * 
	 * WeakHashMap essentially adds a dynamic field to CompletableFuture holding strong reference to reactive future.
	 */
	private static final Map<CompletableFuture<?>, ReactiveFuture<?>> associations = new WeakHashMap<>();
	@SuppressWarnings("unchecked") public static synchronized <T> ReactiveFuture<T> wrap(CompletableFuture<T> completable) {
		ReactiveFuture<?> cached = associations.get(completable);
		if (cached != null)
			return (ReactiveFuture<T>)cached;
		ReactiveFuture<T> reactive = new ReactiveFuture<T>(completable);
		associations.put(completable, reactive);
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
			 */
			CurrentReactiveScope.block();
			/*
			 * We don't have any fallback value and it might not be correct to fallback to null, so just throw.
			 */
			throw new ReactiveBlockingException();
		}
		return unpack(value);
	}
	public T getNow(T fallback) {
		ReactiveValue<T> value = variable.value();
		if (value.blocking()) {
			/*
			 * Don't propagate blocking. This method is specifically intended to avoid all blocking.
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
				 * 
				 * Throw unchecked variant of TimeoutException to simplify use of the API and to stay consistent with other hookless APIs.
				 */
				throw new UncheckedTimeoutException();
			}
			/*
			 * If timeout has not been reached yet, continue like in get().
			 */
			CurrentReactiveScope.block();
			throw new ReactiveBlockingException();
		}
		return unpack(value);
	}
	@Override public String toString() {
		ReactiveValue<T> value = variable.value();
		if (value.blocking())
			CurrentReactiveScope.block();
		return OwnerTrace.of(this) + " = " + (value.blocking() ? "(pending)" : Objects.toString(value.exception(), Objects.toString(value.result())));
	}
}
