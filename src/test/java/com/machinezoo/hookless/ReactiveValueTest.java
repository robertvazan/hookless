// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import org.junit.*;

public class ReactiveValueTest {
	@Test public void easyConstruction() {
		ReactiveValue<String> v = new ReactiveValue<>("hello", null, false);
		assertEquals("hello", v.result());
		RuntimeException ex = new RuntimeException();
		v = new ReactiveValue<>(null, ex, true);
		assertSame(ex, v.exception());
		assertTrue(v.blocking());
	}
	@Test public void convenienceConstructors() {
		ReactiveValue<String> v = new ReactiveValue<>();
		assertNull(v.result());
		assertFalse(v.blocking());
		v = new ReactiveValue<>("hello");
		assertEquals("hello", v.result());
		v = new ReactiveValue<>("hello", true);
		assertTrue(v.blocking());
		RuntimeException ex = new RuntimeException();
		v = new ReactiveValue<>(ex);
		assertSame(ex, v.exception());
		v = new ReactiveValue<>(ex, true);
		assertTrue(v.blocking());
	}
	@Test public void detectInconsistentValue() {
		assertThrows(IllegalArgumentException.class, () -> new ReactiveValue<>("hello", new RuntimeException(), false));
	}
	@Test public void supportsEquality() {
		String s1 = "hello";
		String s2 = new String(s1);
		assertEquals(new ReactiveValue<>(s1), new ReactiveValue<>(s2));
		assertNotEquals(new ReactiveValue<>("some"), new ReactiveValue<>("other"));
		assertNotEquals(new ReactiveValue<>(s1, true), new ReactiveValue<>(s2, false));
	}
	@Test public void comparesExceptions() {
		Throwable[] same = new Throwable[2];
		for (int i = 0; i < 2; ++i)
			same[i] = new RuntimeException("Test exception");
		assertEquals(new ReactiveValue<>(same[0]), new ReactiveValue<>(same[1]));
		assertNotEquals(new ReactiveValue<>(same[0], true), new ReactiveValue<>(same[1], false));
		assertNotEquals(new ReactiveValue<>(same[0]), new ReactiveValue<>(new RuntimeException()));
		assertNotEquals(new ReactiveValue<>(new IllegalStateException()), new ReactiveValue<>(new RuntimeException()));
		Throwable[] named = new Throwable[2];
		for (int i = 0; i < 2; ++i)
			named[i] = new RuntimeException("Test exception " + (i + 1));
		assertNotEquals(new ReactiveValue<>(named[0]), new ReactiveValue<>(named[1]));
	}
	@Test public void unpack() {
		assertEquals("hello", new ReactiveValue<>("hello").get());
		RuntimeException ex = new RuntimeException();
		try {
			new ReactiveValue<>(ex).get();
			fail();
		} catch (CompletionException cex) {
			assertSame(ex, cex.getCause());
		}
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			new ReactiveValue<>("hello", true).get();
			assertTrue(c.scope().blocked());
		}
	}
	@Test public void pack() {
		assertEquals(new ReactiveValue<>("hello"), ReactiveValue.capture(() -> "hello"));
		RuntimeException ex = new RuntimeException();
		assertEquals(new ReactiveValue<>(ex), ReactiveValue.capture(() -> {
			throw ex;
		}));
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			assertEquals(new ReactiveValue<>("hello", true), ReactiveValue.capture(() -> {
				CurrentReactiveScope.block();
				return "hello";
			}));
		}
	}
}
