// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

/*
 * Accumulates side effects for merging.
 */
public interface ReactiveSideEffectBuffer {
	/*
	 * Key to be merged.
	 */
	ReactiveSideEffectKey key();
	/*
	 * Throws if the key is different. Throws if the side effect is frozen.
	 */
	void merge(ReactiveSideEffect effect);
	/*
	 * Returns null instead of a no-op side effect.
	 */
	ReactiveSideEffect build();
}
