// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental.std;

import java.lang.ref.*;
import java.util.*;
import com.machinezoo.hookless.experimental.*;

public abstract class StandardReactiveData implements ReactiveData {
	private ReactiveVersion version;
	private record Subscriber(WeakReference<ReactiveComputation> computation, long iteration) {}
	private Map<ReactiveNodeKey, Subscriber> subscribers = new HashMap<>();
	protected StandardReactiveData(ReactiveVersion version) {
		this.version = version;
	}
	@Override
	public synchronized ReactiveVersion version() {
		return version;
	}
	@Override
	public synchronized Collection<ReactiveComputation> subscribers() {
		return subscribers.values().stream()
			.map(s -> s.computation.get())
			.filter(Objects::nonNull)
			.toList();
	}
	@Override
	public void subscribe(ReactiveComputation subscriber, long iteration, ReactiveVersion version) {
		boolean invalidate = false;
		synchronized (this) {
			if (this.version.equals(version)) {
				if (subscribers.remove(subscriber.key()) != null)
					throw new IllegalStateException("Computation is already subscribed.");
				subscribers.put(subscriber.key(), new Subscriber(new WeakReference<>(subscriber), iteration));
			} else
				invalidate = true;
		}
		/*
		 * Run invalidation unlocked, because it is a call in reverse stack order.
		 */
		if (invalidate)
			subscriber.invalidate(iteration);
	}
	@Override
	public synchronized void unsubscribe(ReactiveComputation subscriber) {
		subscribers.remove(subscriber.key());
	}
	/*
	 * Must be called from unsynchronized context.
	 */
	protected void track() {
		var computation = ReactiveStack.top();
		if (computation != null)
			computation.track(this, version);
	}
	/*
	 * Must be called from synchronized context.
	 * Returns invalidation batch that must be called outside synchronized context.
	 */
	protected Runnable commit(ReactiveVersion version) {
		if (!this.version.equals(version)) {
			this.version = version;
			if (subscribers.isEmpty())
				return null;
			var invalidated = subscribers;
			subscribers = new HashMap<>();
			return () -> {
				for (var subscriber : invalidated.values()) {
					var computation = subscriber.computation().get();
					if (computation != null)
						computation.invalidate(subscriber.iteration());
				}
			};
		} else
			return null;
	}
}
