// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import com.machinezoo.closeablescope.*;

public class ReactivePinsTest {
	// This test is very similar to the one for ReactiveFreezes, but it's not the same as the two classes behave differently.
	private final ReactivePins p = new ReactivePins();
	// State of ReactivePins can be manipulated explicitly and fully observed.
	@Test
	public void explicit() {
		assertThat(p.keys(), is(empty()));
		assertNull(p.get("key"));
		p.set("key", new ReactiveValue<>("value"));
		assertThat(p.keys(), contains("key"));
		assertEquals(new ReactiveValue<>("value"), p.get("key"));
		p.set("key", new ReactiveValue<>(new RuntimeException()));
		assertThat(p.get("key").exception(), instanceOf(RuntimeException.class));
		p.set("key", null);
		assertThat(p.keys(), is(empty()));
		assertNull(p.get("key"));
	}
	// However, the usual way to use ReactivePins is to call pin().
	@Test
	public void pin() {
		assertEquals("value", p.pin("key", () -> "value"));
		assertThat(p.keys(), contains("key"));
		// The Supplier is not called second time.
		assertEquals("value", p.pin("key", () -> "other"));
	}
	// If the Supplier throws, the exception is also pinned.
	@Test
	public void exception() {
		// ReactiveValue wraps all exceptions in CompletionException.
		CompletionException ce = assertThrows(CompletionException.class, () -> p.pin("key", () -> {
			throw new ArithmeticException();
		}));
		assertThat(ce.getCause(), instanceOf(ArithmeticException.class));
		assertThat(p.keys(), contains("key"));
		// If we try to throw another exception second time around, we will still get the first one.
		ce = assertThrows(CompletionException.class, () -> p.pin("key", () -> {
			throw new IllegalStateException();
		}));
		assertThat(ce.getCause(), instanceOf(ArithmeticException.class));
	}
	@Test
	public void blocking() {
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			// Contrary to freezing, pinning does not capture blocking. Pinning is instead disabled when blocking.
			assertEquals("value", p.pin("key", () -> {
				CurrentReactiveScope.block();
				return "value";
			}));
			assertTrue(s.blocked());
			assertThat(p.keys(), is(empty()));
			// The same applies to pre-existing blocking condition.
			assertEquals("other", p.pin("key", () -> "other"));
			assertThat(p.keys(), is(empty()));
			// Explicitly setting blocking value is not allowed.
			assertThrows(IllegalArgumentException.class, () -> p.set("key", new ReactiveValue<>("blocking", true)));
		}
	}
	@Test
	public void inheritance() {
		ReactivePins gp = new ReactivePins();
		gp.set("X", new ReactiveValue<>("X in grandparent"));
		gp.set("Y", new ReactiveValue<>("Y in grandparent"));
		ReactivePins pp = new ReactivePins();
		pp.parent(gp);
		pp.set("X", new ReactiveValue<>("X in parent"));
		p.parent(pp);
		// Pin is taken from the nearest ancestor that has the key.
		assertEquals("X in parent", p.pin("X", () -> "random"));
		assertEquals("Y in grandparent", p.pin("Y", () -> "random"));
		// Child does not store pins that were simply returned from an ancestor.
		assertThat(p.keys(), is(empty()));
		// Child can override pins defined by ancestors.
		p.set("X", new ReactiveValue<>("X in child"));
		assertEquals("X in child", p.pin("X", () -> "random"));
		// Override in the child has no effect on the parent.
		assertEquals("X in parent", pp.pin("X", () -> "random"));
	}
	@Test
	public void invalidation() {
		// Invalidation applies to pins, not the pin container, so ensure it is non-empty.
		p.pin("key", () -> "value");
		// Pins are initially valid.
		assertTrue(p.valid());
		// Invalidation simply marks them as invalid.
		p.invalidate();
		assertFalse(p.valid());
		// Invalidation is inherited.
		ReactivePins c = new ReactivePins();
		c.parent(p);
		assertFalse(c.valid());
		ReactivePins gc = new ReactivePins();
		gc.parent(c);
		assertFalse(gc.valid());
		// Pins are valid if the whole hierarchy is valid.
		ReactivePins vp = new ReactivePins();
		p.pin("key", () -> "value");
		c.parent(vp);
		assertTrue(c.valid());
		assertTrue(gc.valid());
		// Empty pin collection is always valid since there are no pins to invalidate.
		ReactivePins ep = new ReactivePins();
		ep.invalidate();
		c.parent(ep);
		assertTrue(ep.valid());
		assertTrue(c.valid());
		assertTrue(gc.valid());
	}
}
