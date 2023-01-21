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
	 * - instanceof on the side effect itself
	 * - instanceof on the key
	 * - examining contents of the key
	 */
	ReactiveSideEffectKey key();
}
