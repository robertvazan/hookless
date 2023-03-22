// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.experimental;

import java.util.*;
import com.machinezoo.closeablescope.*;
import com.machinezoo.stagean.*;

/*
 * This could be later replaced with JEP 429: Scoped Values (https://openjdk.org/jeps/429).
 * Scoped values support rebinding and they work better with JEP 425: Virtual Threads.
 * The try-with-resources API would then have to be abandoned in favor of ScopedValue.run().
 * Dependency reporting by multiple threads can be handled by having a map of collectors keyed by thread ID.
 * Pins would not be necessary and freezes could be done with scoped values as well.
 */
@ApiIssue("Alternative to top() that returns no-op consumer instead of null.")
@ApiIssue("Stack trace of the current thread, of other threads, and a way to enumerate threads.")
public class ReactiveStack {
	private static ThreadLocal<Deque<ReactiveComputationNode>> current = ThreadLocal.withInitial(ArrayDeque::new);
	public static ReactiveComputationNode top() {
		return current.get().peekLast();
	}
	public static CloseableScope push(ReactiveComputationNode computation) {
		Objects.requireNonNull(computation);
		var stack = current.get();
		/*
		 * No checks. Allow starting the same computation twice.
		 */
		stack.addLast(computation);
		return () -> {
			/*
			 * Try the fast path first.
			 */
			if (stack.peekLast() == computation)
				stack.removeLast();
			else {
				/*
				 * Tolerate double closing. Do not report any error if the computation is not on the stack anymore.
				 * Remove from the end in order to tolerate the rare case of doubly started computations.
				 * Tolerate stopping computations out of order. This may happen if computations are stopped explicitly
				 * rather than via try-with-resources.
				 */
				stack.removeLastOccurrence(computation);
			}
		};
	}
}
