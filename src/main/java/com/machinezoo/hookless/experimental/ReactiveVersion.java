// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

public interface ReactiveVersion extends ReactiveKey {
	/*
	 * All version objects are convertible to a hash.
	 * 
	 * Hashes are allowed to be equal only if versions are equal.
	 * There must be no way to accidentally create the same hash from different version objects,
	 * even from two different types of version objects that just happen to share parameters.
	 * 
	 * That implies:
	 * - Hash must be generated using cryptographic hashing algorithm like SHA-256, even for data that fits in 256 bits.
	 * - Type of version object must be included in the hash. Hashing parameters alone is not enough.
	 * - Null parameters must result in unique hashes distinct from hashing empty arrays/strings.
	 * 
	 * Hashes are perfectly reproducible during single program run and mostly reproducible between runs.
	 * Occasional changes are permitted between runs, especially after code changes.
	 */
	ReactiveVersionHash toHash();
}
