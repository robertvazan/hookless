// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import com.machinezoo.closeablescope.*;

public class ReactiveFreezesTest {
	private final ReactiveFreezes f = new ReactiveFreezes();
	// State of ReactiveFreezes can be manipulated explicitly and fully observed.
	@Test
	public void explicit() {
		assertThat(f.keys(), is(empty()));
		assertNull(f.get("key"));
		f.set("key", new ReactiveValue<>("value"));
		assertThat(f.keys(), contains("key"));
		assertEquals(new ReactiveValue<>("value"), f.get("key"));
		f.set("key", new ReactiveValue<>(new RuntimeException()));
		assertThat(f.get("key").exception(), instanceOf(RuntimeException.class));
		f.set("key", null);
		assertThat(f.keys(), is(empty()));
		assertNull(f.get("key"));
	}
	// However, the usual way to use ReactiveFreezes is to call freeze().
	@Test
	public void freeze() {
		assertEquals("value", f.freeze("key", () -> "value"));
		assertThat(f.keys(), contains("key"));
		// The Supplier is not called second time.
		assertEquals("value", f.freeze("key", () -> "other"));
	}
	// If the Supplier throws, the exception is also frozen.
	@Test
	public void exception() {
		// ReactiveValue wraps all exceptions in CompletionException.
		CompletionException ce = assertThrows(CompletionException.class, () -> f.freeze("key", () -> {
			throw new ArithmeticException();
		}));
		assertThat(ce.getCause(), instanceOf(ArithmeticException.class));
		assertThat(f.keys(), contains("key"));
		// If we try to throw another exception second time around, we will still get the first one.
		ce = assertThrows(CompletionException.class, () -> f.freeze("key", () -> {
			throw new IllegalStateException();
		}));
		assertThat(ce.getCause(), instanceOf(ArithmeticException.class));
	}
	// Frozen ReactiveValue of course includes the blocking flag.
	@Test
	public void captureBlocking() {
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			assertEquals("value", f.freeze("key", () -> {
				CurrentReactiveScope.block();
				return "value";
			}));
			assertTrue(s.blocked());
			assertTrue(f.get("key").blocking());
		}
	}
	// If the frozen value is marked as blocking for whatever reason, the blocking flag is propagated into the current computation.
	// This is a contrived example using explicit manipulation API. See below for a realistic example.
	@Test
	public void propagateBlocking() {
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			f.set("key", new ReactiveValue<>("value", true));
			assertFalse(s.blocked());
			f.freeze("key", () -> "other");
			assertTrue(s.blocked());
		}
	}
	// This is a realistic example of blocking flag propagation.
	@Test
	public void blockingScenario() {
		// Consider two nested scopes. The inner one is non-blocking. The two share one ReactiveFreezes object.
		try (CloseableScope c1 = new ReactiveScope().enter()) {
			try (CloseableScope c2 = ReactiveScope.nonblocking()) {
				// Blocking freeze of course marks the inner scope as blocked.
				assertEquals("value", CurrentReactiveScope.freeze("key", () -> {
					CurrentReactiveScope.block();
					return "value";
				}));
				assertTrue(CurrentReactiveScope.blocked());
			}
			// The blocking flag is not propagated to the outer scope. This is how non-blocking scope works.
			assertFalse(CurrentReactiveScope.blocked());
			// If we however make use of the freeze again, this time outside of the non-blocking scope, then blocking is propagated.
			assertEquals("value", CurrentReactiveScope.freeze("key", () -> "other"));
			assertTrue(CurrentReactiveScope.blocked());
		}
	}
	@Test
	public void inheritance() {
		ReactiveFreezes gp = new ReactiveFreezes();
		gp.set("X", new ReactiveValue<>("X in grandparent"));
		gp.set("Y", new ReactiveValue<>("Y in grandparent"));
		ReactiveFreezes p = new ReactiveFreezes();
		p.parent(gp);
		p.set("X", new ReactiveValue<>("X in parent"));
		f.parent(p);
		// Freeze is taken from the nearest ancestor that has the key.
		assertEquals("X in parent", f.freeze("X", () -> "random"));
		assertEquals("Y in grandparent", f.freeze("Y", () -> "random"));
		// Child does not store freezes that were simply returned from an ancestor.
		assertThat(f.keys(), is(empty()));
		// Child can override freezes defined by ancestors.
		f.set("X", new ReactiveValue<>("X in child"));
		assertEquals("X in child", f.freeze("X", () -> "random"));
		// Override in the child has no effect on the parent.
		assertEquals("X in parent", p.freeze("X", () -> "random"));
	}
}
