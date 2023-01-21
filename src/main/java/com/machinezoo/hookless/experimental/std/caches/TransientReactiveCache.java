// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.caches;

import java.util.*;
import com.machinezoo.hookless.experimental.*;

public class TransientReactiveCache implements ReactiveCache {
	public static final TransientReactiveCache DEFAULT = new TransientReactiveCache();
	@Override
	public ReactiveNode materialize(ReactiveNodeKey key) {
		return key.reactiveConfig().instantiate();
	}
	@Override
	public Collection<ReactiveNode> nodes() {
		return Collections.emptyList();
	}
}
