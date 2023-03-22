// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.markers;

import java.util.*;
import com.machinezoo.hookless.experimental.*;
import com.machinezoo.hookless.experimental.std.caches.*;

public class ReactiveMarkerConfig implements ReactiveObjectConfig {
	private final ReactiveMarker key;
	public ReactiveMarkerConfig(ReactiveMarker key) {
		Objects.requireNonNull(key);
		this.key = key;
	}
	@Override
	public ReactiveMarker key() {
		return key;
	}
	@Override
	public ReactiveCache cache() {
		return TransientReactiveCache.DEFAULT;
	}
	@Override
	public ReactiveMarkerNode instantiate() {
		return new ReactiveMarkerNode(key);
	}
}
