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
	 * This method is guaranteed to return the current version. There is no delay of visibility of changes between data and version.
	 * Calls are non-reactive. To track the version as a dependency, call touch().
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
	 * Enforce touch() API on all data nodes.
	 * This gives persistent caches a way to probe their dependencies without loading them to memory.
	 * The default implementation should work for most reactive data nodes as long as version() is efficient.
	 */
	default void touch() {
		var consumer = ReactiveStack.top();
		if (consumer != null)
			consumer.track(this, version());
	}
}
