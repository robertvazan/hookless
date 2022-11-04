// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import com.machinezoo.closeablescope.*;

public class ReactiveStateMachineTest {
	@Test
	public void initial() {
		// When initial value is provided, it is returned regardless of the supplier output.
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveStateMachine<String> sm = ReactiveStateMachine.supply(new ReactiveValue<>("hi", true), v::get);
		assertEquals(new ReactiveValue<>("hi", true), sm.output());
		// By default, this initial value is a blocking exception.
		ReactiveValue<String> dv = ReactiveStateMachine.supply(v::get).output();
		assertTrue(dv.blocking());
		assertThat(dv.exception(), instanceOf(ReactiveBlockingException.class));
	}
	@Test
	public void advance() {
		// After advancement, state machine returns output of the supplier.
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveStateMachine<String> sm = ReactiveStateMachine.supply(v::get);
		sm.advance();
		assertEquals(new ReactiveValue<>("hello"), sm.output());
		// Changes to reactive variables behind the supplier have no effect without advancement.
		v.set("hi");
		assertEquals(new ReactiveValue<>("hello"), sm.output());
		// Further advancement makes changes visible.
		sm.advance();
		assertEquals(new ReactiveValue<>("hi"), sm.output());
		// Redundant advancement has no effect.
		sm.advance();
		assertEquals(new ReactiveValue<>("hi"), sm.output());
	}
	@Test
	public void output() {
		// Blocking is captured in the inner reactive computation. There doesn't have to be any outer reactive computation at all.
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("hello", true));
		ReactiveStateMachine<String> sm = ReactiveStateMachine.supply(v::get);
		sm.advance();
		assertEquals(new ReactiveValue<>("hello", true), sm.output());
		// Exceptions are captured as well.
		v.value(new ReactiveValue<>(new ArithmeticException()));
		sm.advance();
		assertThat(sm.output().exception(), instanceOf(CompletionException.class));
		assertThat(sm.output().exception().getCause(), instanceOf(ArithmeticException.class));
		// Wrapping in CompletionException is done by the reactive variable.
		// Directly throwing from the supplier gives us unwrapped exception.
		sm = ReactiveStateMachine.supply(() -> {
			throw new ArithmeticException();
		});
		sm.advance();
		assertThat(sm.output().exception(), instanceOf(ArithmeticException.class));
	}
	@Test
	public void invalidation() {
		// State machine starts in an invalid state.
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("hello", true));
		ReactiveStateMachine<String> sm = ReactiveStateMachine.supply(v::get);
		assertFalse(sm.valid());
		// Advancement moves it to a valid state.
		sm.advance();
		assertTrue(sm.valid());
		// Variable changes invalidate it again.
		v.set("hi");
		assertFalse(sm.valid());
		// And further advancement makes it valid again.
		sm.advance();
		assertTrue(sm.valid());
	}
	@Test
	public void pinning() {
		// As an example, we will pin value of AtomicInteger.
		AtomicInteger n = new AtomicInteger();
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("hello", true));
		ReactiveStateMachine<String> sm = ReactiveStateMachine.supply(() -> {
			// Pin before accessing the variable, because the variable contains blocking value. Blocking would disable pinning.
			int p = CurrentReactiveScope.pin("pk", n::incrementAndGet);
			return v.get() + " " + p;
		});
		// First (blocking) computation executes the pin supplier.
		sm.advance();
		assertEquals(new ReactiveValue<>("hello 1", true), sm.output());
		// Second (blocking) computation keeps the pinned value from last blocking computation.
		v.value(new ReactiveValue<>("hi", true));
		sm.advance();
		assertEquals(new ReactiveValue<>("hi 1", true), sm.output());
		// Third (non-blocking) computation still uses the pin.
		v.set("bye");
		sm.advance();
		assertEquals(new ReactiveValue<>("bye 1"), sm.output());
		// Pin supplier was called only once during the three computations.
		assertEquals(1, n.get());
		// Pins are not kept after non-blocking computation completes.
		v.set("hello");
		sm.advance();
		assertEquals(new ReactiveValue<>("hello 2"), sm.output());
	}
	@Test
	public void immediate() {
		// Create computation that also invalidates its own dependencies. This often happens in practice.
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveStateMachine<String> sm = ReactiveStateMachine.supply(() -> {
			String s = v.get();
			v.set("hi");
			return s;
		});
		// We can observe computation output, but we also immediately see the output is already invalidated.
		sm.advance();
		assertEquals(new ReactiveValue<>("hello"), sm.output());
		assertFalse(sm.valid());
		// As soon as there is no real change to the variable, we get output that remains valid.
		sm.advance();
		assertEquals(new ReactiveValue<>("hi"), sm.output());
		assertTrue(sm.valid());
	}
	private static class Triggers {
		ReactiveTrigger v = new ReactiveTrigger();
		ReactiveTrigger o = new ReactiveTrigger();
	}
	@Test
	public void reactivity() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveStateMachine<String> sm = ReactiveStateMachine.supply(new ReactiveValue<>("initial"), v::get);
		// Initially, valid() and output() have their starting values and no reactive invalidation is signaled.
		BiFunction<Boolean, String, Triggers> check = (vs, o) -> {
			Triggers t = new Triggers();
			ReactiveScope s = new ReactiveScope();
			try (CloseableScope c = s.enter()) {
				assertEquals(vs, sm.valid());
				// Accessing valid() never blocks even if the inner computation blocks.
				assertFalse(CurrentReactiveScope.blocked());
				t.v.arm(s.versions());
			}
			s = new ReactiveScope();
			try (CloseableScope c = s.enter()) {
				assertEquals(new ReactiveValue<>(o), sm.output());
				// Accessing output() never blocks even if the inner computation blocks.
				assertFalse(CurrentReactiveScope.blocked());
				t.o.arm(s.versions());
			}
			// There are no reactive invalidations without cause.
			assertFalse(t.v.fired());
			assertFalse(t.o.fired());
			return t;
		};
		Triggers t = check.apply(false, "initial");
		Runnable advance = () -> {
			ReactiveScope s = new ReactiveScope();
			try (CloseableScope c = s.enter()) {
				sm.advance();
				// Advancing the state machine never blocks even if the inner computation blocks.
				assertFalse(CurrentReactiveScope.blocked());
				try (ReactiveTrigger at = new ReactiveTrigger()) {
					at.arm(s.versions());
					// Advancing the state machine never invalidates the current computation.
					assertFalse(at.fired());
				}
			}
		};
		// Advancement both changes the output and marks the current state as valid.
		advance.run();
		assertTrue(t.v.fired());
		assertTrue(t.o.fired());
		t = check.apply(true, "hello");
		// Variable change will invalidate the state machine, but it has no effect on output.
		v.set("hi");
		assertTrue(t.v.fired());
		assertFalse(t.o.fired());
		t = check.apply(false, "hello");
		// Series of changes have the same effect as a single change.
		v.set("bye");
		assertFalse(t.v.fired());
		assertFalse(t.o.fired());
		t = check.apply(false, "hello");
		// Advancement again changes both the output and state validity.
		advance.run();
		assertTrue(t.v.fired());
		assertTrue(t.o.fired());
		t = check.apply(true, "bye");
		// Redundant advancement has no effect.
		advance.run();
		assertFalse(t.v.fired());
		assertFalse(t.o.fired());
	}
	@Test
	public void runnable() {
		// We can also construct the state machine from Runnable.
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveStateMachine<Void> sm = ReactiveStateMachine.run(new ReactiveValue<>(null, true), () -> {
			v.get();
		});
		assertEquals(new ReactiveValue<>(null, true), sm.output());
		assertFalse(sm.valid());
		// Advancement does not change the value of course, but it may have side effects, throw exceptions, and signal blocking.
		sm.advance();
		assertEquals(new ReactiveValue<>(null), sm.output());
		assertTrue(sm.valid());
		v.value(new ReactiveValue<>(new ArithmeticException()));
		assertFalse(sm.valid());
		sm.advance();
		assertThat(sm.output().exception().getCause(), instanceOf(ArithmeticException.class));
		// Initial value is an exception as with Supplier.
		ReactiveValue<Void> dv = ReactiveStateMachine.run(() -> {}).output();
		assertTrue(dv.blocking());
		assertThat(dv.exception(), instanceOf(ReactiveBlockingException.class));
	}
}
