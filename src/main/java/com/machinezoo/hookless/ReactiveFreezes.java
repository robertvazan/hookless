package com.machinezoo.hookless;

import java.util.*;
import java.util.function.*;

/*
 * Freezes get their own object, so that they can be shared between nested scopes if needed.
 * We can also make the internal state observable and modifiable by placing extra methods here.
 * We could have just exposed Map<Object, ReactiveValue<?>>, but that would prevent future API improvements.
 */
public class ReactiveFreezes {
	/*
	 * The map is lazily constructed to get some small performance gain,
	 * because freezes are relatively rarely used.
	 * 
	 * We are storing reactive value rather than just plain value, so that we can freeze exceptions.
	 * Blocking flag is captured too, but since the value is unpacked immediately,
	 * the blocking flag gets propagated to the current reactive scope and its storage here is redundant.
	 */
	private Map<Object, ReactiveValue<?>> map;
	/*
	 * Read-only parent collection can be configured.
	 * If it already contains freeze for given key, that freeze is returned instead.
	 * This is useful for controlling how state from nested scopes propagates to the parent scope.
	 */
	private ReactiveFreezes parent;
	public ReactiveFreezes parent() {
		return parent;
	}
	void parent(ReactiveFreezes parent) {
		this.parent = parent;
	}
	/*
	 * Most application code calls freeze() on reactive scope for convenience, but this is the implementation.
	 * 
	 * We have to perform an unchecked cast here. Compiler ensures that callers perform the actual type check.
	 * It's a runtime check, so there could be some surprises at runtime,
	 * but the structure of the API makes such bugs unlikely.
	 */
	@SuppressWarnings("unchecked") public <T> T freeze(Object key, Supplier<T> supplier) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(supplier);
		for (ReactiveFreezes ancestor = parent; ancestor != null; ancestor = ancestor.parent)
			if (ancestor.map != null && ancestor.map.containsKey(key))
				return ((ReactiveValue<T>)ancestor.map.get(key)).get();
		if (map == null)
			map = new HashMap<>();
		else {
			ReactiveValue<?> stored = map.get(key);
			if (stored != null)
				return ((ReactiveValue<T>)stored).get();
		}
		ReactiveValue<T> captured = ReactiveValue.capture(supplier);
		map.put(key, captured);
		return captured.get();
	}
	/*
	 * Internal state of the freeze collection should be fully observable and modifiable.
	 * These methods do not touch parent freeze collection, because that one is accessible through parent().
	 */
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
		/*
		 * Allow removing items by setting null value. It keeps the API simpler and shorter.
		 */
		if (value != null) {
			if (map == null)
				map = new HashMap<>();
			map.put(key, value);
		} else if (map != null)
			map.remove(key);
	}
}
