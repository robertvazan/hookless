// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static java.util.stream.Collectors.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

public class ReactiveScopeTest {
	@Test
	public void observeVariableAccess() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			assertEquals("hello", v.get());
		}
		assertThat(s.versions().stream().map(x -> x.variable()).collect(toList()), contains(v));
	}
	@Test
	public void nest() {
		ReactiveVariable<String> v1 = new ReactiveVariable<>("hello");
		ReactiveVariable<String> v2 = new ReactiveVariable<>("world");
		ReactiveScope s1 = new ReactiveScope();
		ReactiveScope s2 = new ReactiveScope();
		try (ReactiveScope.Computation c1 = s1.enter()) {
			assertEquals("hello", v1.get());
			try (ReactiveScope.Computation c2 = s2.enter()) {
				assertEquals("world", v2.get());
			}
		}
		// The outer scope only records accesses that were not shadowed by inner scope.
		assertThat(s1.versions().stream().map(v -> v.variable()).collect(toList()), contains(v1));
		assertThat(s2.versions().stream().map(v -> v.variable()).collect(toList()), contains(v2));
	}
	@Test
	public void current() {
		assertNull(ReactiveScope.current());
		ReactiveScope s1 = new ReactiveScope();
		ReactiveScope s2 = new ReactiveScope();
		// Merely creating the scope doesn't cause it to be current.
		assertNull(ReactiveScope.current());
		try (ReactiveScope.Computation c1 = s1.enter()) {
			// Computation's scope can be always retrieved from thread-local storage.
			assertSame(s1, ReactiveScope.current());
			try (ReactiveScope.Computation c2 = s2.enter()) {
				// Inner scope shadows outer scope.
				assertSame(s2, ReactiveScope.current());
				// Every computation remembers its own scope.
				assertSame(s1, c1.scope());
				assertSame(s2, c2.scope());
			}
			// Inner scope restored outer scope when it ends.
			assertSame(s1, ReactiveScope.current());
		}
		// Scope is current only until its computation is closed.
		assertNull(ReactiveScope.current());
	}
	@Test
	public void keepFirstVersion() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			assertEquals("hello", v.get());
			v.set("world");
			assertEquals("world", v.get());
			// Of the two accesses, the first one is recorded while the second one is ignored.
			assertEquals(v.version() - 1, c.scope().versions().stream().findFirst().get().number());
		}
	}
	@Test
	public void pickEarlierVersion() {
		// Create a variable with lots of versions.
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		for (int i = 0; i < 10; ++i)
			v.set("world " + i);
		// Simulate collection of unordered versions, e.g. from multiple threads.
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			s.watch(v, 3);
			s.watch(v, 2);
			s.watch(v, 4);
		}
		// Earliest version is kept regardless of insertion order.
		assertEquals(2, s.versions().stream().findFirst().get().number());
	}
	@Test
	public void ignore() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		try (ReactiveScope.Computation c1 = new ReactiveScope().enter()) {
			assertEquals("hello", v.get());
			// Ignore variable accesses in the inner scope.
			try (ReactiveScope.Computation c2 = ReactiveScope.ignore()) {
				assertEquals("world", new ReactiveVariable<>("world").get());
			}
			// Variable from the ignoring scope was not recorded.
			assertThat(c1.scope().versions().stream().map(x -> x.variable()).collect(toList()), contains(v));
		}
	}
	@Test
	public void block() {
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			// Scope is not blocked by default.
			assertFalse(c.scope().blocked());
			// First block() call marks it as blocked.
			c.scope().block();
			assertTrue(c.scope().blocked());
		}
	}
	@Test
	public void nonblocking() {
		try (ReactiveScope.Computation c1 = new ReactiveScope().enter()) {
			// Attempting to block in a non-blocking inner scope has no effect on the outer scope.
			try (ReactiveScope.Computation c2 = ReactiveScope.nonblocking()) {
				c2.scope().block();
			}
			assertFalse(c1.scope().blocked());
		}
	}
	@Test
	public void freeze() {
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			// Evaluate the Supplier the first time around.
			assertEquals("value", s.freeze("key", () -> "value"));
			// Keep returning the same value for the key.
			assertEquals("value", s.freeze("key", () -> "other"));
			// Freezes can be retrieved.
			assertEquals(new ReactiveValue<>("value"), s.freezes().get("key"));
			// Pre-existing freezes can be configured for the scope.
			ReactiveFreezes f = new ReactiveFreezes();
			f.set("key", new ReactiveValue<>("hi"));
			s.freezes(f);
			assertSame(f, s.freezes());
			assertEquals("hi", s.freeze("key", () -> "other"));
		}
	}
	@Test
	public void pin() {
		ReactiveScope s1 = new ReactiveScope();
		try (ReactiveScope.Computation c = s1.enter()) {
			// Within single scope, pins behave like freezes.
			assertEquals("value", CurrentReactiveScope.pin("key", () -> "value"));
			assertEquals("value", CurrentReactiveScope.pin("key", () -> "other"));
		}
		// After pinning in one scope, we can retrieve the pin in another scope sharing the same pins.
		ReactiveScope s2 = new ReactiveScope();
		s2.pins(s1.pins());
		try (ReactiveScope.Computation c = s2.enter()) {
			assertEquals("value", CurrentReactiveScope.pin("key", () -> "other"));
			// Pins remain valid until invalidated by blocking.
			assertTrue(s2.pins().valid());
			s2.block();
			assertFalse(s2.pins().valid());
			// Once the computation is blocked, pins are not stored, but previously created pins are unaffected.
			assertEquals("value", CurrentReactiveScope.pin("key", () -> "other"));
			assertEquals("hello", CurrentReactiveScope.pin("alt", () -> "hello"));
			assertThat(s2.pins().keys(), contains("key"));
			// The pins are however downgraded to freezes and thus stable throughout the current computation.
			assertThat(s2.freezes().keys(), containsInAnyOrder("key", "alt"));
			assertEquals("hello", CurrentReactiveScope.pin("alt", () -> "hi"));
		}
	}
}
