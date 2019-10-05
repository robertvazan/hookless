// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.Assert.*;
import org.junit.*;

public class CurrentReactiveScopeTest {
	@Test public void propagateBlocking() {
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			CurrentReactiveScope.block();
		}
		assertTrue(s.blocked());
	}
	@Test public void ignoreBlocking() {
		// no exception
		CurrentReactiveScope.block();
	}
	@Test public void testBlocked() {
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			assertFalse(CurrentReactiveScope.blocked());
			CurrentReactiveScope.block();
			assertTrue(CurrentReactiveScope.blocked());
		}
	}
	@Test public void fallbackBlocked() {
		assertFalse(CurrentReactiveScope.blocked());
	}
	@Test public void propagateFreeze() {
		ReactiveScope s = new ReactiveScope();
		String frozen;
		try (ReactiveScope.Computation c = s.enter()) {
			// use String constructor to ensure we get a new instance each time
			frozen = CurrentReactiveScope.freeze("key", () -> new String("value"));
			assertSame(frozen, CurrentReactiveScope.freeze("key", () -> new String("value")));
		}
		assertSame(frozen, s.freezes().get("key").result());
	}
	@Test public void fallbackFreeze() {
		String fallback = CurrentReactiveScope.freeze("key", () -> new String("value"));
		assertEquals("value", fallback);
		assertNotSame(fallback, CurrentReactiveScope.freeze("key", () -> new String("value")));
	}
	@Test public void propagatePin() {
		ReactiveScope s = new ReactiveScope();
		String pinned;
		try (ReactiveScope.Computation c = s.enter()) {
			// use String constructor to ensure we get a new instance each time
			pinned = CurrentReactiveScope.pin("key", () -> new String("value"));
			assertSame(pinned, CurrentReactiveScope.pin("key", () -> new String("value")));
		}
		assertSame(pinned, s.pins().get("key").get());
	}
	@Test public void fallbackPin() {
		String fallback = CurrentReactiveScope.pin("key", () -> new String("value"));
		assertEquals("value", fallback);
		assertNotSame(fallback, CurrentReactiveScope.pin("key", () -> new String("value")));
	}
}
