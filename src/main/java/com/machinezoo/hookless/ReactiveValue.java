// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/*
 * This is the equivalent of CompletableFuture for reactive programming:
 * - CompletableFuture.get() = ReactiveValue.get()
 * - CompletableFuture.getNow() = ReactiveValue.result()
 * - CompletableFuture.isDone() = !ReactiveValue.blocking()
 * 
 * It makes it possible to communicate results of reactive computations outside of reactive scope.
 */
public class ReactiveValue<T> {
	private final T result;
	public T result() {
		return result;
	}
	private final Throwable exception;
	public Throwable exception() {
		return exception;
	}
	private final boolean blocking;
	public boolean blocking() {
		return blocking;
	}
	/*
	 * We can either tolerate non-null result combined with non-null exception or throw.
	 * We decide to throw, because this is unlikely to be accidental. It is nearly always indicative of a bug.
	 */
	public ReactiveValue(T result, Throwable exception, boolean blocking) {
		if (result != null && exception != null)
			throw new IllegalArgumentException("Cannot set both the result and the exception.");
		this.result = result;
		this.exception = exception;
		this.blocking = blocking;
	}
	/*
	 * If necessary, reactive value can be unpacked into current reactive scope by calling get().
	 * It is therefore a bridge between reactive and non-reactive world.
	 */
	public T get() {
		if (blocking)
			CurrentReactiveScope.block();
		if (exception != null)
			throw new CompletionException(exception);
		return result;
	}
	/*
	 * We also provide the opposite operation. Reactive value can capture value or exception and blocking flag.
	 * 
	 * We provide only capture from Supplier, because there is usually some value to capture.
	 * Operations without result can have their exception and blocking captured by using Void result type.
	 */
	public static <T> ReactiveValue<T> capture(Supplier<T> supplier) {
		if (ReactiveScope.current() != null)
			return captureScoped(supplier);
		else {
			/*
			 * Some code, especially tests, runs without reactive scope but still needs to capture blocking flag.
			 * We will create temporary scope for such cases. Everything in the scope is discarded except the blocking flag.
			 */
			try (ReactiveScope.Computation computation = new ReactiveScope().enter()) {
				return captureScoped(supplier);
			}
		}
	}
	private static <T> ReactiveValue<T> captureScoped(Supplier<T> supplier) {
		try {
			/*
			 * Due to java evaluation order, blocking is checked only after the supplier runs.
			 */
			return new ReactiveValue<>(supplier.get(), CurrentReactiveScope.blocked());
		} catch (Throwable ex) {
			return new ReactiveValue<>(ex, CurrentReactiveScope.blocked());
		}
	}
	/*
	 * Equality testing is a difficult choice. Comparing the result objects may be too expensive.
	 * Exceptions normally cannot be compared. We have to fully unwind them in order to compare them, which is expensive.
	 * The other option is to compare exceptions by reference only, but that is inconsistent.
	 * But without equality comparisons, we would get too many invalidations everywhere.
	 * So we support equality here and let callers decide whether to use it.
	 */
	@Override public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || !(obj instanceof ReactiveValue))
			return false;
		@SuppressWarnings("unchecked") ReactiveValue<T> other = (ReactiveValue<T>)obj;
		/*
		 * Cheapest comparisons first. This speeds up comparisons with negative result.
		 */
		if (blocking != other.blocking)
			return false;
		if ((exception != null) != (other.exception != null))
			return false;
		if ((result != null) != (other.result != null))
			return false;
		return Objects.equals(result, other.result) && Objects.equals(dump(exception), dump(other.exception));
	}
	@Override public int hashCode() {
		/*
		 * Reactive value is unlikely to be used as a hash key. We are free to make this inefficient.
		 */
		return Objects.hash(result, dump(exception), blocking);
	}
	/*
	 * Some reactive computations only use fast reference equality.
	 * This method does as much equality checking as possible without running any expensive operations.
	 */
	public boolean same(ReactiveValue<?> other) {
		return other != null && result == other.result && exception == other.exception && blocking == other.blocking;
	}
	/*
	 * There are more efficient ways to compare exceptions, but this crude solution will work for now.
	 * It has no impact on performance in case there is no exception.
	 * If there is an exception, nobody expects stellar performance.
	 */
	private static String dump(Throwable exception) {
		if (exception == null)
			return null;
		StringWriter writer = new StringWriter();
		exception.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}
	@Override public String toString() {
		return (exception == null ? Objects.toString(result) : exception.toString()) + (blocking ? " [blocking]" : "");
	}
	/*
	 * Convenience constructors.
	 */
	public ReactiveValue() {
		this(null, null, false);
	}
	public ReactiveValue(T result) {
		this(result, null, false);
	}
	public ReactiveValue(Throwable exception) {
		this(null, exception, false);
	}
	public ReactiveValue(T result, boolean blocking) {
		this(result, null, blocking);
	}
	public ReactiveValue(Throwable exception, boolean blocking) {
		this(null, exception, blocking);
	}
}
