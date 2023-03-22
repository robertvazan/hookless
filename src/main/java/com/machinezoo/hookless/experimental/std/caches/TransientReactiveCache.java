// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.caches;

import java.util.*;
import com.machinezoo.hookless.experimental.*;

public class TransientReactiveCache implements ReactiveCache {
	public static final TransientReactiveCache DEFAULT = new TransientReactiveCache();
	@Override
	public ReactiveObjectNode materialize(ReactiveObject key) {
		return key.reactiveConfig().instantiate();
	}
	@Override
	public Collection<ReactiveObjectNode> nodes() {
		return Collections.emptyList();
	}
}
