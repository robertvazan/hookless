// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.markers;

import com.machinezoo.hookless.experimental.*;

public interface ReactiveMarker extends ReactiveData {
	@Override
	default ReactiveMarkerConfig reactiveConfig() {
		return new ReactiveMarkerConfig(this);
	}
	default void touch() {
		new ReactiveMarkerNode(this).track();
	}
}
