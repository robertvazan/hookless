// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.function.*;
import com.machinezoo.hookless.utils.*;

/*
 * Reactive computations sometimes need to control other reactive computations.
 * This can be done with reactive primitives (scope, trigger, pins),
 * but these primitives are intended for integration with non-reactive code and for implementing new reactive constructs.
 * It is also possible to run the controlled reactive computation in its own reactive thread,
 * but such solution sacrifices too much control and makes synchronous interactions with the controlled computation hard.
 * 
 * This class is a high-level wrapper around reactive primitives (scope, trigger, pins, variable)
 * that exposes high-level reactive API to the controlling reactive computation.
 * The API allows single-stepping through the controlled reactive computation. Hence the state machine metaphor.
 */
public class ReactiveStateMachine<T> {
	/*
	 * Last output of the controlled reactive computation.
	 */
	private final ReactiveVariable<T> output;
	/*
	 * False when the state machine needs to make another step.
	 * 
	 * We could alternatively hold the entire publicly visible state in one reactive variable to minimize number of dependencies.
	 * We choose two reactive variables mostly for simplicity with minor benefits in traceability and fine-grained dependencies.
	 */
	private final ReactiveVariable<Boolean> valid = OwnerTrace
		.of(new ReactiveVariable<>(false))
		.parent(this)
		.tag("role", "valid")
		.target();
	/*
	 * The controlling reactive computation is expected to query the state.
	 * This creates dependency on the state machine, so that we can wake up the controlling computation
	 * when step is made or when last output is invalidated due to dependency change.
	 */
	public ReactiveValue<T> output() {
		/*
		 * This records dependency on the state machine. No dependency is recorded on the controlled computation itself.
		 */
		return output.value();
	}
	public boolean valid() {
		return valid.get();
	}
	/*
	 * Controlling reactive computation needs to inspect output at least to observe exceptions and blocking.
	 * We can just as well add result to make the reactive value complete, which means the controlled computation is defined by Supplier.
	 * 
	 * We however want to allow Runnable. Constructor overload would work despite some ambiguity,
	 * but it would be unwieldy for Runnable because of the type parameter.
	 * We have to use named constructors. Constructor taking the Supplier is also named for consistency.
	 */
	private final Supplier<T> supplier;
	private ReactiveStateMachine(ReactiveValue<T> initial, Supplier<T> supplier) {
		Objects.requireNonNull(initial);
		Objects.requireNonNull(supplier);
		OwnerTrace.of(this).alias("machine");
		this.supplier = supplier;
		output = OwnerTrace
			.of(new ReactiveVariable<>(initial))
			.parent(this)
			.tag("role", "output")
			.target();
	}
	public static <T> ReactiveStateMachine<T> supply(ReactiveValue<T> initial, Supplier<T> supplier) {
		return new ReactiveStateMachine<>(initial, supplier);
	}
	public static <T> ReactiveStateMachine<T> supply(Supplier<T> supplier) {
		return supply(new ReactiveValue<>(null, null, true), supplier);
	}
	public static ReactiveStateMachine<Void> run(ReactiveValue<Void> initial, Runnable runnable) {
		Objects.requireNonNull(runnable);
		return supply(initial, () -> {
			runnable.run();
			return null;
		});
	}
	public static ReactiveStateMachine<Void> run(Runnable runnable) {
		return run(new ReactiveValue<>(null, null, true), runnable);
	}
	/*
	 * Reactive state machine is implemented using reactive primitives: scope, trigger, and pins.
	 */
	private ReactiveTrigger trigger;
	private ReactivePins pins;
	/*
	 * After the application detects that the current state is no longer valid, it is expected to trigger the next iteration.
	 * Application can do this on its own schedule or possibly never.
	 * 
	 * We have to lock out other threads to avoid concurrent advancement that would corrupt the state machine.
	 * We can afford to synchronize on the whole state machine for a possibly long time,
	 * because reads from reactive variables are unsynchronized (since reactive variable itself is synchronized)
	 * and invalidation callback never executes concurrently with full advancement.
	 */
	@SuppressWarnings("resource") public synchronized void advance() {
		/*
		 * Non-null trigger indicates the current state is still valid.
		 * Do not advance the state machine in this case as a convenience to application code
		 * that can now try to advance the state machine redundantly without it getting costly.
		 */
		if (trigger != null)
			return;
		ReactiveScope scope = OwnerTrace.of(new ReactiveScope())
			.parent(this)
			.target();
		if (pins != null)
			scope.pins(pins);
		pins = null;
		try (ReactiveScope.Computation computation = scope.enter()) {
			ReactiveValue<T> value = ReactiveValue.capture(supplier);
			/*
			 * We will be sending two invalidations to the controlling reactive computation.
			 * Discourage it first from advancing the state machine by marking its state as valid.
			 * Only then set the output. This prevents unnecessary attempts to advance the state machine.
			 */
			valid.set(true);
			output.value(value);
		}
		if (scope.blocked())
			pins = scope.pins();
		trigger = OwnerTrace
			.of(new ReactiveTrigger()
				.callback(this::invalidate))
			.parent(this)
			.target();
		/*
		 * Arming the trigger can cause it to fire immediately.
		 * We don't worry about that, because our invalidation callback is very fast and non-conflicting.
		 */
		trigger.arm(scope.versions());
	}
	private synchronized void invalidate() {
		if (trigger != null) {
			trigger.close();
			trigger = null;
			valid.set(false);
		}
	}
}
