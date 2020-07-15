// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.function.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.stagean.*;

/*
 * Reactive computation graph needs intermediate nodes that serve as reactive consumers and sources at the same time.
 * These intermediate nodes are usually asynchronous, reflecting changes in dependencies after some delay due to queuing.
 * This class is a synchronous version of such an intermediate node. Reads from lazy objects reflect recent writes.
 * Synchronous nature of this class comes at the cost of numerous limitations.
 * 
 * Lazy or "memo" (from memoization) calls provided supplier once upon first access, caches the result,
 * and then keeps returning the cached result without further invocations of the supplier.
 * The special thing about reactive lazy, besides support for reactive values (especially reactive blocking),
 * is that it can be invalidated when dependencies change. It therefore avoids staleness of its non-reactive counterpart.
 * Invalidation resets reactive lazy to its initial state except that reactive pins are preserved when necessary.
 */
/**
 * Single-value synchronous cache for results of reactive computations.
 * 
 * @param <T>
 *            type of cached result
 */
@StubDocs
public class ReactiveLazy<T> implements Supplier<T> {
	/*
	 * Reactive lazy is a special case of reactive state machine. Its implementation is therefore a trivial wrapper.
	 */
	private final ReactiveStateMachine<T> generator;
	public ReactiveLazy(Supplier<T> supplier) {
		Objects.requireNonNull(supplier);
		OwnerTrace.of(this).alias("lazy");
		generator = OwnerTrace.of(ReactiveStateMachine.supply(supplier))
			.parent(this)
			.target();
	}
	/*
	 * We will expose only unpacked reactive value as is common in reactive code.
	 * Application can always capture the full reactive value explicitly if it wants to.
	 * 
	 * No need to synchronize here, because reactive state machine is already synchronized.
	 * The code below will have the same effect regardless of whether this method is synchronized
	 * and that is true also in case the state is invalidated immediately after being computed.
	 */
	@Override
	public T get() {
		/*
		 * Always advance to ensure the returned value reflects latest writes.
		 * Reactive state machine is smart enough to avoid unnecessary advancement and to handle concurrent invocations.
		 */
		generator.advance();
		/*
		 * By the time we get here, another thread might have performed another advancement.
		 * We don't care, because we are returning fresh value in either case.
		 */
		return generator.output().get();
	}
	@Override
	public String toString() {
		return OwnerTrace.of(this) + " = " + generator.output();
	}
}
