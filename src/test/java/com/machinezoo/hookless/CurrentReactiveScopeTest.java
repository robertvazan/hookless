// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

public class CurrentReactiveScopeTest {
	@Test
	public void block() {
		// Propagate blocking to the current scope.
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			CurrentReactiveScope.block();
			assertTrue(c.scope().blocked());
		}
		// No effect and no exception outside of any scope.
		CurrentReactiveScope.block();
	}
	@Test
	public void blocked() {
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			// Read blocking state from the current scope.
			assertFalse(CurrentReactiveScope.blocked());
			CurrentReactiveScope.block();
			assertTrue(CurrentReactiveScope.blocked());
		}
		// Default to false outside of any scope.
		assertFalse(CurrentReactiveScope.blocked());
	}
	@Test
	public void freeze() {
		// Propagate freeze() call to the current scope.
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			assertEquals("value", CurrentReactiveScope.freeze("key", () -> "value"));
			assertEquals("value", CurrentReactiveScope.freeze("key", () -> "other"));
			assertEquals(new ReactiveValue<>("value"), c.scope().freezes().get("key"));
		}
		// Re-evaluate the Supplier each time outside of any reactive scope.
		assertEquals("one", CurrentReactiveScope.freeze("key", () -> "one"));
		assertEquals("two", CurrentReactiveScope.freeze("key", () -> "two"));
	}
	@Test
	public void pin() {
		// Propagate pin() call to the current scope.
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			assertEquals("value", CurrentReactiveScope.pin("key", () -> "value"));
			assertEquals("value", CurrentReactiveScope.pin("key", () -> "other"));
			assertEquals(new ReactiveValue<>("value"), c.scope().pins().get("key"));
		}
		// Re-evaluate the Supplier each time outside of any reactive scope.
		assertEquals("one", CurrentReactiveScope.pin("key", () -> "one"));
		assertEquals("two", CurrentReactiveScope.pin("key", () -> "two"));
	}
}
