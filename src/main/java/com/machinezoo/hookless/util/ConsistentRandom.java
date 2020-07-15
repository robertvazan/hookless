// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.util;

import java.util.*;
import com.machinezoo.stagean.*;

/*
 * It is desirable for reactive computations to produce the same output for the same state of their dependencies.
 * Since random number generators are an invisible variable input to the computation, they break output consistency.
 * This small utility class offers consistent source of randomness that depends only on specified parameters.
 * 
 * It is named "consistent" rather than "reproducible" random, because it only needs to be consistent in one process.
 * Reproducibility would imply repeatability of persistent program output, which would require not only seed consistency
 * but also algorithm consistency, which is complicated to implement and likely less performant than native RNG.
 */
/**
 * Provider of {@link Random} instances that return the same values for the same seeding key.
 */
@NoTests
@StubDocs
public class ConsistentRandom {
	/*
	 * We will return plain Random for now. We could extend the functionality later just like ThreadLocalRandom does.
	 * 
	 * Application is responsible for providing hashable keys that are non-random
	 * and yet unique enough to make consistent RNG produce different output in different contexts.
	 */
	public static Random of(Object... keys) {
		/*
		 * Use Arrays.deepHashCode() instead of Objects.hash(), so that callers can pass in whole arrays.
		 */
		return new Random(Arrays.deepHashCode(keys));
	}
}
