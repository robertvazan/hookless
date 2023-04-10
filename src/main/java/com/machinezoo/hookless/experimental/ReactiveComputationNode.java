// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

import java.util.stream.*;

/*
 * Computations can theoretically terminate when there are no dependencies to watch.
 * Dependencies can be smart enough to not report their version via track() method when they know that version will not change.
 * In practice however, dependencies cannot know whether their version will change across program runs, due to code change, for example.
 * And since computations can be persistent, all dependencies have to continue reporting their versions to handle changes across runs.
 * We could have a special flag attached to version that indicates the version will not change until the program terminates,
 * but the added complexity is not worth the benefit, because most computations will depend on something that can change.
 */
public interface ReactiveComputationNode extends ReactiveObjectNode {
	ReactiveComputation key();
	/*
	 * Number of times this computation has run. Non-negative. Informative. Non-reactive.
	 * There's no computation hash, because many computations do not produce any data.
	 * This is the number of completed iterations, so it's zero while the computation runs for the first time.
	 */
	long iteration();
	/*
	 * Throws if the computation is not currently running.
	 */
	void track(ReactiveDataNode dependency, ReactiveVersion version);
	/*
	 * Throws if the computation is not currently running.
	 * Tolerates null parameter, which is interpreted as a no-op side effect.
	 */
	void consume(ReactiveSideEffect effect);
	/*
	 * Redundant invalidations are silently ignored, because it is normal for several sources to change at the same time.
	 * Iteration number is necessary, because invalidations might arrive when the computation has already moved to the next iteration.
	 * 
	 * Invalidations travel in reverse stack order, which creates the possibility of deadlock.
	 * Computations have to be designed to lock only for very short duration.
	 * They certainly cannot be locked while the computation is running.
	 * If minimal locking is not an option, invalidation handler must forward the invalidation into a thread pool.
	 * Thread pool is an elegant solution, but it prevents the computation from responding instantly, which is often important.
	 */
	void invalidate(long iteration);
	/*
	 * Force refresh. Useful for global invalidations, debugging, and troubleshooting.
	 */
	void invalidate();
	/*
	 * Provide access to observed side effects while the computation is running.
	 */
	ReactiveSideEffect effect(ReactiveSideEffectKey key);
	Stream<ReactiveSideEffect> effects();
}
