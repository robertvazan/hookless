// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import com.machinezoo.closeablescope.*;

public class ReactiveValueTest {
	@Test
	public void constructors() {
		// Main constructor.
		ReactiveValue<String> v = new ReactiveValue<>("hello", null, false);
		assertEquals("hello", v.result());
		RuntimeException ex = new RuntimeException();
		v = new ReactiveValue<>(null, ex, true);
		assertSame(ex, v.exception());
		assertTrue(v.blocking());
		// ReactiveValue cannot carry both a value and an exception.
		assertThrows(IllegalArgumentException.class, () -> new ReactiveValue<>("hello", new RuntimeException(), false));
		// Convenience constructors.
		v = new ReactiveValue<>();
		assertNull(v.result());
		assertFalse(v.blocking());
		v = new ReactiveValue<>("hello");
		assertEquals("hello", v.result());
		v = new ReactiveValue<>("hello", true);
		assertTrue(v.blocking());
		v = new ReactiveValue<>(ex);
		assertSame(ex, v.exception());
		v = new ReactiveValue<>(ex, true);
		assertTrue(v.blocking());
	}
	@Test
	public void equals() {
		// Value equality, not reference equality.
		String s1 = "hello";
		String s2 = new String(s1);
		assertEquals(new ReactiveValue<>(s1), new ReactiveValue<>(s2));
		assertNotEquals(new ReactiveValue<>("some"), new ReactiveValue<>("other"));
		// Tolerate nulls.
		assertEquals(new ReactiveValue<>(), new ReactiveValue<>());
		// Compare blocking flag.
		assertNotEquals(new ReactiveValue<>(s1, true), new ReactiveValue<>(s2, false));
	}
	@Test
	public void equalsForExceptions() {
		// This has to be done in a loop, because two separate exception initializations would differ in line numbers.
		Throwable[] same = new Throwable[2];
		for (int i = 0; i < 2; ++i)
			same[i] = new RuntimeException("Test exception");
		assertEquals(new ReactiveValue<>(same[0]), new ReactiveValue<>(same[1]));
		// Compare exception type.
		assertNotEquals(new ReactiveValue<>(new IllegalStateException()), new ReactiveValue<>(new RuntimeException()));
		// Compare line number.
		assertNotEquals(new ReactiveValue<>(same[0]), new ReactiveValue<>(new RuntimeException("Test exception")));
		// Compare blocking flag for exceptions.
		assertNotEquals(new ReactiveValue<>(same[0], true), new ReactiveValue<>(same[1], false));
		// Compare exception message.
		Throwable[] named = new Throwable[2];
		for (int i = 0; i < 2; ++i)
			named[i] = new RuntimeException("Test exception " + (i + 1));
		assertNotEquals(new ReactiveValue<>(named[0]), new ReactiveValue<>(named[1]));
	}
	@Test
	public void same() {
		// Reference equality, not value equality.
		String s = "hello";
		assertFalse(new ReactiveValue<>(s).same(new ReactiveValue<>(new String(s))));
		assertTrue(new ReactiveValue<>(s).same(new ReactiveValue<>(s)));
		// Compare blocking flag.
		assertFalse(new ReactiveValue<>(s, true).same(new ReactiveValue<>(s, false)));
		// Compare exception by reference. This has to be done in a loop like in equals() test.
		Throwable[] ex = new Throwable[2];
		for (int i = 0; i < 2; ++i)
			ex[i] = new RuntimeException("Test exception");
		assertTrue(new ReactiveValue<>(ex[0]).same(new ReactiveValue<>(ex[0])));
		assertFalse(new ReactiveValue<>(ex[0]).same(new ReactiveValue<>(ex[1])));
	}
	@Test
	public void unpack() {
		assertEquals("hello", new ReactiveValue<>("hello").get());
		// Exceptions are wrapped.
		RuntimeException ex = new RuntimeException();
		CompletionException ce = assertThrows(CompletionException.class, () -> new ReactiveValue<>(ex).get());
		assertSame(ex, ce.getCause());
		// Blocking flag is propagated.
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			new ReactiveValue<>("hello", true).get();
			assertTrue(s.blocked());
		}
	}
	@Test
	public void pack() {
		// Capture simple value.
		assertEquals(new ReactiveValue<>("hello"), ReactiveValue.capture(() -> "hello"));
		// Capture exception.
		RuntimeException ex = new RuntimeException();
		assertEquals(new ReactiveValue<>(ex), ReactiveValue.capture(() -> {
			throw ex;
		}));
		// Capture blocking.
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			assertEquals(new ReactiveValue<>("hello", true), ReactiveValue.capture(() -> {
				CurrentReactiveScope.block();
				return "hello";
			}));
			// Allow the blocking to be observed by current computation.
			assertTrue(s.blocked());
		}
	}
}
