// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.machinezoo.closeablescope.*;
import com.machinezoo.stagean.*;

/*
 * This is the equivalent of CompletableFuture for reactive programming:
 * - CompletableFuture.get() = ReactiveValue.get()
 * - CompletableFuture.getNow() = ReactiveValue.result()
 * - CompletableFuture.isDone() = !ReactiveValue.blocking()
 * Better not say that in the javadoc as it might be more confusing than enlightening.
 */
/**
 * Container for output of reactive computation consisting of return value, exception,
 * and <a href="https://hookless.machinezoo.com/blocking">reactive blocking</a> flag.
 * {@code ReactiveValue} can be split into its constituent components by calling
 * {@link #result()}, {@link #exception()}, and {@link #blocking()}.
 * It can be recreated from these components by calling {@link #ReactiveValue(Object, Throwable, boolean)}
 * or some other constructor. {@code ReactiveValue} is immutable.
 * <p>
 * Reactive code usually takes the form of a method and communicates its output like a method,
 * i.e. via return value or an exception. Reactive code may additionally signal
 * <a href="https://hookless.machinezoo.com/blocking">reactive blocking</a>
 * by calling {@link CurrentReactiveScope#block()}.
 * Return value, exception, and signaling of reactive blocking constitutes implicit output of reactive computation.
 * {@code ReactiveValue} offers an explicit representation of the same.
 * Conversion between explicit and implicit representations is performed
 * by methods {@link #get()} and {@link #capture(Supplier)}.
 * <p>
 * {@code ReactiveValue} does not carry reactive dependencies. Use {@link ReactiveScope} for that.
 * 
 * @param <T>
 *            type of the result carried by this {@code ReactiveValue}
 * 
 * @see ReactiveVariable
 * @see ReactiveScope
 */
@DraftDocs("link to reactive value/output articles")
public class ReactiveValue<T> {
	private final T result;
	/**
	 * Gets the return value component of this {@code ReactiveValue}.
	 * Only one of {@link #result()} and {@link #exception()} can be non-{@code null}.
	 * 
	 * @return return value component of {@code ReactiveValue}
	 * 
	 * @see #exception()
	 * @see #get()
	 */
	public T result() {
		return result;
	}
	private final Throwable exception;
	/**
	 * Gets the exception component of this {@code ReactiveValue}.
	 * Only one of {@link #result()} and {@link #exception()} can be non-{@code null}.
	 * 
	 * @return exception component of {@code ReactiveValue}
	 * 
	 * @see #result()
	 * @see #get()
	 */
	public Throwable exception() {
		return exception;
	}
	private final boolean blocking;
	/**
	 * Gets the <a href="https://hookless.machinezoo.com/blocking">reactive blocking</a> flag from this {@code ReactiveValue}.
	 * Blocking flag is set if this {@code ReactiveValue} represents output of reactive computation
	 * that signaled blocking during its execution by calling {@link CurrentReactiveScope#block()}.
	 * 
	 * @return {@code true} if this {@code ReactiveValue} represents output of blocking reactive computation, {@code false} otherwise
	 * 
	 * @see #get()
	 * @see <a href="https://hookless.machinezoo.com/blocking">Reactive blocking</a>
	 */
	public boolean blocking() {
		return blocking;
	}
	/*
	 * We can either tolerate non-null result combined with non-null exception or throw.
	 * We decide to throw, because this is unlikely to be accidental. It is nearly always indicative of a bug.
	 */
	/**
	 * Constructs new {@code ReactiveValue} from return value, exception, and <a href="https://hookless.machinezoo.com/blocking">reactive blocking</a> flag.
	 * Only one of {@code result} and {@code exception} can be non-{@code null}.
	 * The parameters can be later retrieved via {@link #result()}, {@link #exception()}, and {@link #blocking()}.
	 * 
	 * @param result
	 *            component representing return value of reactive computation that can be later retrieved via {@link #result()}
	 * @param exception
	 *            component representing exception thrown by reactive computation that can be later retrieved via {@link #exception()}
	 * @param blocking
	 *            {@code true} if the constructed {@code ReactiveValue} should represent
	 *            <a href="https://hookless.machinezoo.com/blocking">blocking</a> reactive computation, {@code false} otherwise
	 * @throws IllegalArgumentException
	 *             if both {@code result} and {@code exception} are non-{@code null}
	 * 
	 * @see #capture(Supplier)
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
	/**
	 * Unpacks explicit reactive output represented by this {@code ReactiveValue} into implicit reactive output.
	 * If {@link #exception()} is not {@code null}, it is thrown wrapped in {@link CompletionException}. Otherwise {@link #result()} is returned.
	 * In either case, if {@link #blocking()} is {@code true}, {@link CurrentReactiveScope#block()} is called.
	 * 
	 * @return value of {@link #result()}
	 * @throws CompletionException
	 *             if {@link #exception()} is not {@code null}
	 * 
	 * @see #result()
	 * @see #exception()
	 * @see #blocking()
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
	/**
	 * Captures implicit reactive output of provided {@link Supplier} and returns it encapsulated in new {@code ReactiveValue}.
	 * If the {@code supplier} throws, returned {@code ReactiveValue} will have {@link #exception()} set to the caught exception.
	 * Otherwise the {@code ReactiveValue} will have {@link #result()} set to value returned from the {@code supplier}.
	 * <p>
	 * If the {@code supplier} <a href="https://hookless.machinezoo.com/blocking">reactively blocks</a> by calling {@link CurrentReactiveScope#block()},
	 * {@link #blocking()} flag will be set on the returned {@code ReactiveValue}.
	 * This method obtains blocking flag by calling {@link CurrentReactiveScope#blocked()} after calling the {@code supplier},
	 * which means the returned {@code ReactiveValue} will have {@link #blocking()} flag set also
	 * if the current {@link ReactiveScope} was already blocked by the time this method was called.
	 * This is reasonable behavior, because the {@code supplier} might be itself derived from information
	 * produced by blocking operations executed earlier during the current reactive computation,
	 * which means that {@code supplier}'s output cannot be trusted to be non-blocking.
	 * <p>
	 * If there is no current {@link ReactiveScope}, i.e. {@link ReactiveScope#current()} returns {@code null},
	 * this method creates temporary {@link ReactiveScope} and executes the {@code supplier} in it,
	 * so that blocking flag can be captured. This is particularly useful in unit tests.
	 * 
	 * @param <T>
	 *            type of value returned by the {@code supplier}
	 * @param supplier
	 *            reactive code to execute
	 * @return {@code ReactiveValue} encapsulating implicit reactive output of the {@code supplier}
	 * 
	 * @see #ReactiveValue(Object, Throwable, boolean)
	 */
	public static <T> ReactiveValue<T> capture(Supplier<T> supplier) {
		if (ReactiveScope.current() != null)
			return captureScoped(supplier);
		else {
			/*
			 * Some code, especially tests, runs without reactive scope but still needs to capture blocking flag.
			 * We will create temporary scope for such cases. Everything in the scope is discarded except the blocking flag.
			 */
			try (CloseableScope computation = new ReactiveScope().enter()) {
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
	 * Exceptions normally cannot be compared. We have to examine the full stack trace in order to compare them, which is expensive.
	 * The other option is to compare exceptions by reference only, but that is inconsistent.
	 * But without equality comparisons, we would get too many invalidations everywhere.
	 * So we support equality here and let callers decide whether to use it.
	 */
	/**
	 * Compares this {@code ReactiveValue} to another object for equality.
	 * {@code ReactiveValue} can only equal another {@code ReactiveValue}.
	 * Two {@code ReactiveValue} instances are equal if their {@link #result()}, {@link #exception()}, and {@link #blocking()} flags are equal.
	 * Value equality is used for both {@link #result()} and {@link #exception()}.
	 * Two exceptions are equal when their stringified form (including stack trace and causes) compares equal.
	 * <p>
	 * Full value equality checking may be expensive or even undesirable.
	 * Use {@link #same(ReactiveValue)} to compute shallow reference equality.
	 * 
	 * @param obj
	 *            object to compare this {@code ReactiveValue} to or {@code null}
	 * @return {@code true} if the objects compare equal, {@code false} otherwise
	 * 
	 * @see #same(ReactiveValue)
	 * @see #hashCode()
	 */
	@Override
	public boolean equals(Object obj) {
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
	/**
	 * Computes hash code of this {@code ReactiveValue}.
	 * Hash code is calculated in such a way that if two {@code ReactiveValue} instances are equal
	 * as checked by {@link #equals(Object)}, then their hash codes are equal too.
	 * This makes {@code ReactiveValue} usable as a key in a {@link Map}.
	 * <p>
	 * Both {@link #result()} and {@link #exception()} are included in hash code calculation.
	 * Exceptions are hashed in such a way that two exceptions with the same stringified form
	 * (including stack traces and causes) will have the same hash code.
	 * 
	 * @return hash code of this {@code ReactiveValue}
	 * 
	 * @see #equals(Object)
	 */
	@Override
	public int hashCode() {
		/*
		 * Reactive value is unlikely to be used as a hash key. We are free to make this inefficient.
		 */
		return Objects.hash(result, dump(exception), blocking);
	}
	/*
	 * Some reactive computations only use fast reference equality.
	 * This method does as much equality checking as possible without running any expensive operations.
	 */
	/**
	 * Checks reference equality between two {@code ReactiveValue} instances.
	 * Another {@code ReactiveValue} is reference-equal to this instance according to this method
	 * if it is not {@code null} and its {@link #result()}, {@link #exception()}, and {@link #blocking()}
	 * components are all reference-equal to corresponding components of this {@code ReactiveValue}.
	 * <p>
	 * This method is useful when {@link #equals(Object)} would be too expensive or where reference equality is desirable.
	 * 
	 * @param other
	 *            {@code ReactiveValue} to compare this instance to or {@code null}
	 * @return {@code true} if the two {@code ReactiveValue} instances are reference-equal, {@code false} otherwise
	 * 
	 * @see #equals(Object)
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
	/**
	 * Returns a string representation of this {@code ReactiveValue}.
	 * The returned string is suitable for debug output and includes
	 * string representation of {@link #result()} or {@link #exception()}
	 * as well as indication whether the {@code ReactiveValue} is {@link #blocking()}.
	 * 
	 * @return string representation of this {@code ReactiveValue}
	 */
	@Override
	public String toString() {
		return (exception == null ? Objects.toString(result) : exception.toString()) + (blocking ? " [blocking]" : "");
	}
	/*
	 * Convenience constructors.
	 */
	/**
	 * Constructs new {@code ReactiveValue}.
	 * The new {@code ReactiveValue} represents reactive computation that successfully completed with {@code null} result
	 * and without <a href="https://hookless.machinezoo.com/blocking">blocking</a>.
	 * The {@code ReactiveValue} will have {@code null} {@link #result()} and {@link #exception()} and {@code false} {@link #blocking()} flag.
	 * 
	 * @see #ReactiveValue(Object, Throwable, boolean)
	 */
	public ReactiveValue() {
		this(null, null, false);
	}
	/**
	 * Constructs new {@code ReactiveValue} from return value.
	 * The new {@code ReactiveValue} represents reactive computation that successfully completed
	 * without <a href="https://hookless.machinezoo.com/blocking">blocking</a>.
	 * The {@code ReactiveValue} will have {@code null} {@link #exception()} and {@code false} {@link #blocking()} flag.
	 * 
	 * @param result
	 *            return value of the reactive computation the new {@code ReactiveValue} represents
	 * 
	 * @see #ReactiveValue(Object, Throwable, boolean)
	 */
	public ReactiveValue(T result) {
		this(result, null, false);
	}
	/**
	 * Constructs new {@code ReactiveValue} from exception.
	 * The new {@code ReactiveValue} represents reactive computation that threw an exception
	 * without <a href="https://hookless.machinezoo.com/blocking">blocking</a>.
	 * The {@code ReactiveValue} will have {@code null} {@link #result()} and {@code false} {@link #blocking()} flag.
	 * 
	 * @param exception
	 *            exception thrown by the reactive computation the new {@code ReactiveValue} represents
	 * 
	 * @see #ReactiveValue(Object, Throwable, boolean)
	 */
	public ReactiveValue(Throwable exception) {
		this(null, exception, false);
	}
	/**
	 * Constructs new {@code ReactiveValue} from return value and <a href="https://hookless.machinezoo.com/blocking">blocking</a> flag.
	 * The new {@code ReactiveValue} represents reactive computation that successfully completed and possibly signaled blocking.
	 * The {@code ReactiveValue} will have {@code null} {@link #exception()}.
	 * 
	 * @param result
	 *            return value of the reactive computation the new {@code ReactiveValue} represents
	 * @param blocking
	 *            {@code true} if the constructed {@code ReactiveValue} should represent
	 *            <a href="https://hookless.machinezoo.com/blocking">blocking</a> reactive computation, {@code false} otherwise
	 * 
	 * @see #ReactiveValue(Object, Throwable, boolean)
	 */
	public ReactiveValue(T result, boolean blocking) {
		this(result, null, blocking);
	}
	/**
	 * Constructs new {@code ReactiveValue} from exception and <a href="https://hookless.machinezoo.com/blocking">blocking</a> flag.
	 * The new {@code ReactiveValue} represents reactive computation that threw an exception and possibly signaled blocking.
	 * The {@code ReactiveValue} will have {@code null} {@link #result()}.
	 * 
	 * @param exception
	 *            exception thrown by the reactive computation the new {@code ReactiveValue} represents
	 * @param blocking
	 *            {@code true} if the constructed {@code ReactiveValue} should represent
	 *            <a href="https://hookless.machinezoo.com/blocking">blocking</a> reactive computation, {@code false} otherwise
	 * 
	 * @see #ReactiveValue(Object, Throwable, boolean)
	 */
	public ReactiveValue(Throwable exception, boolean blocking) {
		this(null, exception, blocking);
	}
}
