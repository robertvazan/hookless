// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

import java.util.*;

/*
 * Cache does not really have to keep the mapping from keys to nodes.
 * Some nodes do not track any state, so they can be completely transient, although that's not ideal for debugging.
 */
public interface ReactiveCache {
	ReactiveObjectNode materialize(ReactiveObject key);
	Collection<ReactiveObjectNode> nodes();
}
