// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.constants;

import java.util.*;
import com.machinezoo.hookless.experimental.*;

public class ReactiveConstantNode implements ReactiveDataNode {
	private final ReactiveConstant key;
	public ReactiveConstantNode(ReactiveConstant key) {
		this.key = key;
	}
	@Override
	public ReactiveConstant key() {
		return key;
	}
	@Override
	public ReactiveVersion version() {
		return key.reactiveConfig().version();
	}
	@Override
	public Collection<ReactiveComputationNode> subscribers() {
		return Collections.emptyList();
	}
	@Override
	public void subscribe(ReactiveComputationNode subscriber, long iteration, ReactiveVersion version) {
	}
	@Override
	public void unsubscribe(ReactiveComputationNode subscriber) {
	}
	public void track() {
		var computation = ReactiveStack.top();
		if (computation != null)
			computation.track(this, key.reactiveConfig().version());
	}
}
