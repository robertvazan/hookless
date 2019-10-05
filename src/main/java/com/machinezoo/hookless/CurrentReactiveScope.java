package com.machinezoo.hookless;

import java.util.function.*;

/*
 * Since reactive code can run outside of reactive scope, for example during tests,
 * all code would be normally required to constantly check ReactiveScope.current() for null.
 * To avoid that, we offer static methods that provide reasonable fallback for null scope.
 */
public class CurrentReactiveScope {
	public static void block() {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			current.block();
	}
	public static boolean blocked() {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			return current.blocked();
		else
			return false;
	}
	public static <T> T freeze(Object key, Supplier<T> supplier) {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			return current.freeze(key, supplier);
		else
			return supplier.get();
	}
	public static <T> T pin(Object key, Supplier<T> supplier) {
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			return current.pin(key, supplier);
		else
			return supplier.get();
	}
}
