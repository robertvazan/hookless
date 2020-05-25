// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

public class ReactiveBlockingExceptionTest {
	@Test public void constructors() {
		ReactiveBlockingException e = new ReactiveBlockingException();
		assertNull(e.getMessage());
		assertNull(e.getCause());
		e = new ReactiveBlockingException("message");
		assertEquals("message", e.getMessage());
		assertNull(e.getCause());
		e = new ReactiveBlockingException(new ArithmeticException());
		assertNull(e.getMessage());
		assertThat(e.getCause(), instanceOf(ArithmeticException.class));
		e = new ReactiveBlockingException("message", new ArithmeticException());
		assertEquals("message", e.getMessage());
		assertThat(e.getCause(), instanceOf(ArithmeticException.class));
	}
	@Test public void block() {
		// Merely calling the constructor does not block the current computation.
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			new ReactiveBlockingException();
			assertFalse(c.scope().blocked());
		}
		// Calling block() however does both blocking and throwing.
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block());
			assertTrue(c.scope().blocked());
			assertNull(e.getMessage());
			assertNull(e.getCause());
		}
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block("message"));
			assertTrue(c.scope().blocked());
			assertEquals("message", e.getMessage());
			assertNull(e.getCause());
		}
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block(new ArithmeticException()));
			assertTrue(c.scope().blocked());
			assertNull(e.getMessage());
			assertThat(e.getCause(), instanceOf(ArithmeticException.class));
		}
		try (ReactiveScope.Computation c = new ReactiveScope().enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block("message", new ArithmeticException()));
			assertTrue(c.scope().blocked());
			assertEquals("message", e.getMessage());
			assertThat(e.getCause(), instanceOf(ArithmeticException.class));
		}
	}
}
