// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.function.*;
import com.machinezoo.closeablescope.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.stagean.*;

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
/**
 * Monitoring and control over another reactive computation.
 * 
 * @param <T>
 *            output type of the computation
 */
@StubDocs
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
		OwnerTrace.of(this).alias("statemachine");
		this.supplier = supplier;
		output = OwnerTrace
			.of(new ReactiveVariable<>(initial)
				/*
				 * Disable equality checking in the variable.
				 * Some uses of this class (e.g. ReactiveWorker) need to inspect every output even if it is equal.
				 * This is also a safe choice for performance as equality checks can be slow.
				 * 
				 * We could expose equality configuration API in the future,
				 * but it is unlikely to be useful for the relatively low-level tasks this class is used for.
				 */
				.equality(false))
			.parent(this)
			.tag("role", "output")
			.target();
	}
	public static <T> ReactiveStateMachine<T> supply(ReactiveValue<T> initial, Supplier<T> supplier) {
		return new ReactiveStateMachine<>(initial, supplier);
	}
	public static <T> ReactiveStateMachine<T> supply(Supplier<T> supplier) {
		/*
		 * We don't know whether null is a reasonable fallback. Throwing is always correct (although not very efficient).
		 * Constructing exceptions is expensive, but we will favor useful stack trace over fast preallocated exception object.
		 */
		return supply(new ReactiveValue<>(new ReactiveBlockingException(), true), supplier);
	}
	public static ReactiveStateMachine<Void> run(ReactiveValue<Void> initial, Runnable runnable) {
		Objects.requireNonNull(runnable);
		return supply(initial, () -> {
			runnable.run();
			return null;
		});
	}
	public static ReactiveStateMachine<Void> run(Runnable runnable) {
		return run(new ReactiveValue<>(new ReactiveBlockingException(), true), runnable);
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
	@SuppressWarnings("resource")
	public synchronized void advance() {
		/*
		 * Do not advance the state machine if it is still valid. This is a convenience to application code
		 * that can now try to advance the state machine redundantly without it getting costly.
		 * 
		 * Here we have to be careful. We cannot just read valid() flag as that would create reactive dependency
		 * that would be invalidated a few lines below when the valid() flag is set to true.
		 * Such immediate invalidation would force another controlling (outer) reactive computation to run immediately after the current one ends.
		 * While such overhead is common in reactive code and it is usually acceptable, it can be very wasteful here in some important use cases,
		 * for example in ReactiveLazy where it would double compute cost of all reactive computations that read new/changed ReactiveLazy.
		 *
		 * We cannot just check for non-null trigger either as that would make the check completely non-reactive.
		 * The controlling (outer) computation wouldn't run again when the state is invalidated and advancement would stop forever.
		 *
		 * Instead of creating dependency on the full valid() flag, we will reactively depend only on trigger state.
		 * The difference is that trigger state only tracks validity of the last controlled (inner) computation
		 * while the valid() flag tracks all current and future state of the whole reactive state machine.
		 * Trigger state can change only in one direction from not fired to fired while valid() changes both ways.
		 * This reduction in the scope of the dependency is sufficient to avoid redundant reactive computations
		 * while keeping the dependency wide enough to ensure the state machine appears to be fully reactive.
		 * 
		 * Trigger itself is of course non-reactive, because it is a low-level reactive primitive.
		 * So how do we depend on its state? We will read valid() flag but only after we have already set it to true.
		 * This breaks the basic principle of reactive programming that dependencies are recorded before reads,
		 * but it is safe here, because it is equivalent to a scenario, in which another thread advances the state machine
		 * and the current thread executed shortly afterwards, reads the valid() flag (which is true), and returns without advancing.
		 * 
		 * This solution is so efficient that a lot of code can just blindly advance all the time without ever checking valid().
		 * This may be actually more performant thanks to the reduced dependency optimization.
		 */
		if (trigger != null) {
			/*
			 * If the trigger is non-null, then valid() is true and we can just return without advancing.
			 * We will record dependency on valid() to ensure that the controlling (outer) computation
			 * tries to advance the state machine again when valid() becomes false.
			 */
			valid.get();
			return;
		}
		ReactiveScope scope = OwnerTrace.of(new ReactiveScope())
			.parent(this)
			.target();
		if (pins != null)
			scope.pins(pins);
		pins = null;
		try (CloseableScope computation = scope.enter()) {
			ReactiveValue<T> value = ReactiveValue.capture(supplier);
			/*
			 * We will be sending two invalidations to the controlling reactive computation.
			 * We will first discourage redundant advancement by setting valid() to true.
			 * Only then we set the output. This prevents unnecessary attempts to advance the state machine.
			 */
			valid.set(true);
			output.value(value);
		}
		/*
		 * As mentioned above, we will create dependency on valid() to ensure the controlling (outer) computation
		 * tries to advance again when the current state is invalidated, i.e. when valid() is set to false.
		 * We have to do this after setting valid() to true above to avoid immediately invalidating current computation.
		 * We also have to do it before arming the trigger, because trigger could fire immediately (inline)
		 * and such firing involves setting valid() to false, by which time the dependency on valid() must already exist.
		 * We have to be additionally careful not to create the dependency inside the controlled (inner) computation.
		 */
		valid.get();
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
	@Override
	public String toString() {
		return OwnerTrace.of(this) + " = " + output.value();
	}
}
