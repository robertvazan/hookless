// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import com.machinezoo.closeablescope.*;

public class ReactiveBlockingExceptionTest {
	@Test
	public void constructors() {
		ReactiveBlockingException e = new ReactiveBlockingException();
		assertNull(e.getMessage());
		assertNull(e.getCause());
		e = new ReactiveBlockingException("message");
		assertEquals("message", e.getMessage());
		assertNull(e.getCause());
		ArithmeticException ae = new ArithmeticException();
		e = new ReactiveBlockingException(ae);
		assertEquals(ae.toString(), e.getMessage());
		assertThat(e.getCause(), instanceOf(ArithmeticException.class));
		e = new ReactiveBlockingException("message", new ArithmeticException());
		assertEquals("message", e.getMessage());
		assertThat(e.getCause(), instanceOf(ArithmeticException.class));
	}
	@Test
	public void block() {
		// Merely calling the constructor does not block the current computation.
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			new ReactiveBlockingException();
			assertFalse(s.blocked());
		}
		// Calling block() however does both blocking and throwing.
		s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block());
			assertTrue(s.blocked());
			assertNull(e.getMessage());
			assertNull(e.getCause());
		}
		s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block("message"));
			assertTrue(s.blocked());
			assertEquals("message", e.getMessage());
			assertNull(e.getCause());
		}
		s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block(new ArithmeticException()));
			assertTrue(s.blocked());
			assertNull(e.getMessage());
			assertThat(e.getCause(), instanceOf(ArithmeticException.class));
		}
		s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			ReactiveBlockingException e = assertThrows(ReactiveBlockingException.class, () -> ReactiveBlockingException.block("message", new ArithmeticException()));
			assertTrue(s.blocked());
			assertEquals("message", e.getMessage());
			assertThat(e.getCause(), instanceOf(ArithmeticException.class));
		}
	}
}
