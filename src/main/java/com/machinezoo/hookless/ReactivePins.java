// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.function.*;
import com.machinezoo.stagean.*;

/*
 * Container for reactive pins that span multiple blocking computations. Rationale is similar to the one for reactive freezes.
 * 
 * Contrary to older versions of hookless, pinned objects are no longer notified when reactive computation completes.
 * This "close" event is theoretically useful, but it adds a lot of complexity and makes reactive scopes hard to compose.
 * It was previously used in reactive time, but it was removed at the cost of small CPU/memory overhead.
 */
/**
 * Container for pinned outputs of reactive computations.
 */
@StubDocs
public class ReactivePins {
	/*
	 * Equivalents of corresponding code in reactive freezes.
	 */
	private Map<Object, ReactiveValue<?>> map;
	private ReactivePins parent;
	public ReactivePins parent() {
		return parent;
	}
	void parent(ReactivePins parent) {
		this.parent = parent;
	}
	@SuppressWarnings("unchecked")
	public <T> T pin(Object key, Supplier<T> supplier) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(supplier);
		for (ReactivePins ancestor = this; ancestor != null; ancestor = ancestor.parent) {
			if (ancestor.map != null) {
				ReactiveValue<?> stored = ancestor.map.get(key);
				if (stored != null)
					return ((ReactiveValue<T>)stored).get();
			}
		}
		if (map == null)
			map = new HashMap<>();
		ReactiveValue<T> captured = ReactiveValue.capture(supplier);
		/*
		 * If the computation inside the supplier blocks, there's no point in creating a pin for it.
		 * Pins don't get an opportunity to complete their blocking supplier in future computation.
		 * Pins can only represent finished operations that don't wait for anything anymore.
		 * That's why we avoid recording blocking pins here and return supplier's result directly every time.
		 * Code in the supplier is then allowed to complete its blocking operations in future computations.
		 * Once that happens, we happily record the result of the supplier as a new pin.
		 * 
		 * Reactive scope's pin() method records every accessed pin as a freeze.
		 * As a side-effect of that, blocking computations are downgraded to freezes automatically.
		 * So even though we return early here, the returned result will be stable throughout the current computation.
		 */
		if (captured.blocking())
			return captured.get();
		map.put(key, captured);
		return captured.get();
	}
	public Set<Object> keys() {
		if (map == null)
			return Collections.emptySet();
		return map.keySet();
	}
	public ReactiveValue<?> get(Object key) {
		if (map == null)
			return null;
		return map.get(key);
	}
	public void set(Object key, ReactiveValue<?> value) {
		Objects.requireNonNull(key);
		if (value != null) {
			/*
			 * Pins cannot capture blocking. It only makes sense that blocking value cannot be explicitly set.
			 */
			if (value.blocking())
				throw new IllegalArgumentException();
			if (map == null)
				map = new HashMap<>();
			map.put(key, value);
		} else if (map != null)
			map.remove(key);
	}
	/*
	 * Pins are constructed using lambdas that may reference final variables previously produced by current reactive computation.
	 * Even if we wrap the lambda in nested reactive scope, we wouldn't be able to capture dependencies for these variables.
	 * Pins therefore implicitly depend on all data that was previously accessed by current reactive computation.
	 * 
	 * Worse yet, since pins can access data from other pins that may have been captured in some previous blocking computation,
	 * pins implicitly depend on all data that was accessed so far in any of the previous blocking computations.
	 * And since the current computation is free to use the pins to derive more data,
	 * the entirety of the current computation depends on all data accessed during current or any of the previous blocking computations.
	 * 
	 * If we set up a trigger to listen on all versions collected during all previous blocking computations,
	 * the trigger will fire immediately, because some version from past computations is always outdated
	 * as otherwise we wouldn't be running new computation now.
	 * Such immediate firing would create busy loop where the same computation would run again and again.
	 * 
	 * In order to allow the computation to halt for a while, we set up triggers for blocking computations
	 * to only monitor versions collected during that one blocking computation.
	 * We don't let triggers monitor versions from previous computations.
	 * 
	 * The case for final computation (the one that doesn't block) is a bit more complicated.
	 * In this case, we should set up trigger that monitors versions collected during any previous blocking computation
	 * in addition to versions collected during the current non-blocking computation.
	 * This would be conceptually correct, because the final result of several such computations
	 * depends on everything accessed during prior blocking computations if pins were used.
	 * But it would be also inefficient, because we know the trigger would fire immediately.
	 * We will instead short-circuit this process by returning single outdated version from the final non-blocking scope.
	 * 
	 * This will force the computation to run once again, but without having any history of past pins or blocking computations.
	 * This time, hopefully, all the data is already cached, so the computation completes on the first try without any blocking.
	 * Even though pins will be collected, they can only depend on the current computation.
	 * We can then finally set up proper trigger with a list of versions from the current computation.
	 * 
	 * And this is where the validity flag defined below comes in. Conceptually, pins can be assumed to be up-to-date
	 * only if this is a non-blocking computation and there was no past blocking computation.
	 * So we start with validity flag set to true and clear it when we encounter blocking computation.
	 * This is done by scope's block() method, which calls invalidate() method on this pin collection.
	 * 
	 * Considering this strange behavior of pins, it is usually better to freeze unless there is a good reason to pin.
	 * When pinning, one must keep in mind that any blocking will cause the whole computation to repeat at least one more time.
	 * Pinning is thus a bit inefficient. On the other hand, if there are blocking computations,
	 * then the final result already requires more than one computation and adding one more is not so bad.
	 * When there are no blocking computations, pins are as efficient as freezes.
	 * If the final result is not monitored for changes (for example in case of reactive servlets),
	 * then pin invalidation is ignored and there is no performance impact.
	 */
	private boolean valid = true;
	public boolean valid() {
		if (parent != null && !parent.valid())
			return false;
		/*
		 * Conceptually, we are invalidating pins, not the pin collection.
		 * So if there are no pins, there can be no invalidated pins either.
		 * That's why we return true here in case the pin collection is empty.
		 */
		return valid || map == null || map.isEmpty();
	}
	public void invalidate() {
		/*
		 * We can skip invalidation if there are no pins since there is nothing to invalidate.
		 * No new pins will be created during this computation, because it has been marked as blocked.
		 * If pins are created during later computations, they can be still invalidated by blocking in that computation.
		 * It should be noted though that it is fairly unlikely for pins to appear only in some computations for the same code.
		 * This is therefore a corner case optimization and we better avoid it in order to spare us some corner case bugs.
		 */
		valid = false;
	}
	@Override
	public String toString() {
		return getClass().getSimpleName() + ": " + (map != null ? map.toString() : "(empty)");
	}
}
