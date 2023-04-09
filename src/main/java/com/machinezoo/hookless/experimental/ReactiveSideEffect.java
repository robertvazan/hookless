// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

import java.io.*;

/*
 * Side effect represents function output that bypasses the formal arguments, return value, and exceptions.
 * It's intended to capture function output that would be otherwise written into a buffer, context object, or console.
 * Side effect is never a no-op. Null is used in place of no-op side effects.
 * 
 * Side effect is not itself a key, because keys are expected to be small, which might not be the case for some side effects.
 * Side effects are however required to be serializable and sufficiently transparent to support custom serialization.
 * Side effects also have to be immutable and preferably also stringifiable.
 * Equatability can be offered where it simplifies code that handles side effects.
 */
public interface ReactiveSideEffect extends Serializable {
	/*
	 * Side effects with the same key are merged.
	 * 
	 * Although side effects can be often identified by type, i.e. via instanceof on the side effect itself,
	 * this cannot be guaranteed to always be the case, for example in case of reusable wrappers or keyed side effects.
	 * Keys are therefore the ultimate way to identify side effects. This does not however preclude simpler mechanisms.
	 * 
	 * Side effect identification in order of preference:
	 * - instanceof on the data object
	 * - instanceof on the key
	 * - examining contents of the key
	 */
	ReactiveSideEffectKey key();
	/*
	 * Merges this side effect with a subsequent one and returns the result.
	 * Side effects are immutable. This side effect is not modified.
	 * Throws if the two side effects have different keys.
	 * Returns null if the result is a no-op. Returns this if the other side effect is null.
	 * 
	 * If merging is performed often on large side effects, persistent collections can help with performance.
	 * Appending one-element side effects to a long list would result in quadratic performance with standard collections.
	 * We could provide some sort of accumulator API to help with merging efficiency,
	 * but performance improvement is questionable and simpler API is currently preferred.
	 * Side effect data object itself cannot be mutable, because that would prevent clean serialization with records.
	 */
	ReactiveSideEffect merge(ReactiveSideEffect other);
}
