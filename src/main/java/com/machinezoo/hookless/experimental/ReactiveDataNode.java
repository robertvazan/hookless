// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

import java.util.*;

/*
 * Data nodes must be synchronized to ensure consistency between stored data, version, and subscriber map.
 */
public interface ReactiveDataNode extends ReactiveObjectNode {
	ReactiveData key();
	/*
	 * Ideally content hash, but it can be also recursive dependency hash or a random hash indicating change.
	 * Changes only when the node is locked, which should be done only by node's own methods.
	 * Version reads from unlocked node are only informative. Calls are non-reactive.
	 */
	ReactiveVersion version();
	/*
	 * For diagnostic purposes only.
	 */
	Collection<ReactiveComputationNode> subscribers();
	/*
	 * If the version is wrong, invalidation is triggered immediately.
	 * Throws if the subscriber is already subscribed. Both old and new subscription is removed in that case.
	 * This node holds only weak reference to the subscriber.
	 */
	void subscribe(ReactiveComputationNode subscriber, long iteration, ReactiveVersion version);
	/*
	 * Redundant unsubscription is ignored, because subscribers can be removed when the data changes.
	 */
	void unsubscribe(ReactiveComputationNode subscriber);
	/*
	 * Only useful in some trivial cases, for debugging, and for forced invalidation.
	 * In most cases, data read must be synchronized with version read.
	 */
	default void touch() {
		var consumer = ReactiveStack.top();
		if (consumer != null)
			consumer.track(this, version());
	}
}
