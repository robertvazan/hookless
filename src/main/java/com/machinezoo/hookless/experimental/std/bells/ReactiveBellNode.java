// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std.bells;

import com.machinezoo.hookless.experimental.std.*;
import com.machinezoo.hookless.experimental.std.versions.*;

public class ReactiveBellNode extends StandardReactiveDataNode {
	private final ReactiveBell key;
	private long iteration = 1;
	public ReactiveBellNode(ReactiveBell key) {
		super(new ReactiveVersionNumber(1));
		this.key = key;
	}
	@Override
	public ReactiveBell key() {
		return key;
	}
	public synchronized void listen() {
		track();
	}
	public void ring() {
		Runnable invalidation;
		synchronized (this) {
			invalidation = commit(new ReactiveVersionNumber(++iteration));
		}
		if (invalidation != null)
			invalidation.run();
	}
}
