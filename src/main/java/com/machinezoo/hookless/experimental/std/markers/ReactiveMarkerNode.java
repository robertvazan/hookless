// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.markers;

import java.util.*;
import com.machinezoo.hookless.experimental.*;
import com.machinezoo.hookless.experimental.std.versions.*;

public class ReactiveMarkerNode implements ReactiveData {
	private final ReactiveMarker key;
	public ReactiveMarkerNode(ReactiveMarker key) {
		this.key = key;
	}
	@Override
	public ReactiveMarker key() {
		return key;
	}
	@Override
	public ReactiveVersion version() {
		return NullReactiveVersion.INSTANCE;
	}
	@Override
	public Collection<ReactiveComputation> subscribers() {
		return Collections.emptyList();
	}
	@Override
	public void subscribe(ReactiveComputation subscriber, long iteration, ReactiveVersion version) {
	}
	@Override
	public void unsubscribe(ReactiveComputation subscriber) {
	}
	public void track() {
		var computation = ReactiveStack.top();
		if (computation != null)
			computation.track(this, NullReactiveVersion.INSTANCE);
	}
}
