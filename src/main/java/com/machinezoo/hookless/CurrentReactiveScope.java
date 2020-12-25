// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.function.*;
import com.machinezoo.stagean.*;

/*
 * Since reactive code can run outside of reactive scope, for example during tests,
 * all code would be normally required to constantly check ReactiveScope.current() for null.
 * To avoid that, we offer static methods that provide reasonable fallback for null scope.
 */
/**
 * Convenience methods to access current {@link ReactiveScope}.
 * All calls are forwarded to {@link ReactiveScope#current()} if it is not {@code null}.
 * If {@link ReactiveScope#current()} is {@code null}, methods of this class provide safe fallback behavior,
 * which is usually functionally equivalent to creating temporary {@link ReactiveScope}.
 * 
 * @see ReactiveScope
 * @see ReactiveScope#current()
 */
@DraftDocs("reactive freezes link, reactive pins link, reactive computation link")
public class CurrentReactiveScope {
	/**
	 * Mark the current reactive computation as <a href="https://hookless.machinezoo.com/blocking">reactively blocking</a>.
	 * If there's no current reactive computation ({@link ReactiveScope#current()} is {@code null}), this method has no effect.
	 *
	 * @see ReactiveScope#block()
	 * @see ReactiveScope#current()
	 * @see <a href="https://hookless.machinezoo.com/blocking">Reactive blocking</a>
	 */
	public static void block() {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			current.block();
	}
	/**
	 * Returns {@code true} if the current reactive computation is <a href="https://hookless.machinezoo.com/blocking">reactively blocking</a>.
	 * If there's no current reactive computation ({@link ReactiveScope#current()} is {@code null}), this method returns {@code false}.
	 * 
	 * @return {@code true} if the current reactive computation is reactively blocking, {@code false} otherwise
	 *
	 * @see ReactiveScope#blocked()
	 * @see ReactiveScope#current()
	 * @see <a href="https://hookless.machinezoo.com/blocking">Reactive blocking</a>
	 */
	public static boolean blocked() {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			return current.blocked();
		else
			return false;
	}
	/**
	 * Freezes result of specified computation.
	 * On first call with given {@code key}, returns result of evaluating the {@code supplier}.
	 * On subsequent calls within the same reactive computation, returns the same result without calling the {@code supplier}.
	 * If there's no current reactive computation ({@link ReactiveScope#current()} is {@code null}),
	 * this method evaluates {@code supplier} anew on every call.
	 * The {@code supplier} should be always the same for given {@code key}.
	 * 
	 * @param <T>
	 *            type of value returned by the {@code supplier}
	 * @param key
	 *            identifier for particular frozen value, which implements {@link Object#equals(Object)} and {@link Object#hashCode()}
	 * @param supplier
	 *            value supplier to evaluate
	 * @return value computed by {@code supplier} or previously frozen value
	 * 
	 * @see ReactiveScope#freeze(Object, Supplier)
	 * @see ReactiveScope#current()
	 */
	public static <T> T freeze(Object key, Supplier<T> supplier) {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			return current.freeze(key, supplier);
		else
			return supplier.get();
	}
	/**
	 * Pins result of specified computation.
	 * On first call with given {@code key}, returns result of evaluating the {@code supplier}.
	 * On subsequent calls within the same sequence of <a href="https://hookless.machinezoo.com/blocking">blocking</a> reactive computations (pin lifetime),
	 * returns the same result without calling the {@code supplier}.
	 * If there's no current reactive computation ({@link ReactiveScope#current()} is {@code null}),
	 * this method evaluates {@code supplier} anew on every call.
	 * The {@code supplier} should be always the same for given {@code key}.
	 * 
	 * @param <T>
	 *            type of value returned by the {@code supplier}
	 * @param key
	 *            identifier for particular pinned value, which implements {@link Object#equals(Object)} and {@link Object#hashCode()}
	 * @param supplier
	 *            value supplier to evaluate
	 * @return value computed by {@code supplier} or previously pinned value
	 * 
	 * @see ReactiveScope#pin(Object, Supplier)
	 * @see ReactiveScope#current()
	 * @see <a href="https://hookless.machinezoo.com/blocking">Reactive blocking</a>
	 */
	public static <T> T pin(Object key, Supplier<T> supplier) {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			return current.pin(key, supplier);
		else
			return supplier.get();
	}
}
