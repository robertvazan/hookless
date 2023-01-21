// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.caches;

import java.util.*;
import java.util.concurrent.*;
import com.machinezoo.hookless.experimental.*;

public class PermanentReactiveCache implements ReactiveCache {
	public static final PermanentReactiveCache DEFAULT = new PermanentReactiveCache();
	private final ConcurrentMap<ReactiveNodeKey, ReactiveNode> nodes = new ConcurrentHashMap<>();
	@Override
	public ReactiveNode materialize(ReactiveNodeKey key) {
		return nodes.computeIfAbsent(key, k -> k.reactiveConfig().instantiate());
	}
	@Override
	public Collection<ReactiveNode> nodes() {
		/*
		 * Defensive copy to prevent mutations and to protect caller from concurrent changes.
		 */
		return new ArrayList<>(nodes.values());
	}
}
