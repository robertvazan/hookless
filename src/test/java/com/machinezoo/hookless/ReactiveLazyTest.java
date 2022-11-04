// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import com.machinezoo.closeablescope.*;

public class ReactiveLazyTest {
	@Test
	public void fresh() {
		ReactiveVariable<String> a = new ReactiveVariable<>("hello");
		ReactiveVariable<String> b = new ReactiveVariable<>("guys");
		ReactiveLazy<String> l = new ReactiveLazy<>(() -> a.get() + " " + b.get());
		// Initialized upon first access.
		assertEquals("hello guys", l.get());
		// Reflects changes immediately.
		a.set("hi");
		assertEquals("hi guys", l.get());
		b.set("gals");
		assertEquals("hi gals", l.get());
	}
	@Test
	public void lazy() {
		AtomicInteger n = new AtomicInteger();
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		// Supplier is not evaluated until first get().
		ReactiveLazy<String> l = new ReactiveLazy<>(() -> {
			n.incrementAndGet();
			return v.get();
		});
		assertEquals(0, n.get());
		// Value obtained during evaluation is cached.
		assertEquals("hello", l.get());
		assertEquals("hello", l.get());
		assertEquals(1, n.get());
		// Exceptions are cached too.
		v.value(new ReactiveValue<>(new ArithmeticException()));
		assertThrows(CompletionException.class, l::get);
		assertThrows(CompletionException.class, l::get);
		assertEquals(2, n.get());
		// Blocking values are cached too and blocking is repeatedly propagated.
		v.value(new ReactiveValue<>("hi", true));
		for (int i = 0; i < 2; ++i) {
			try (CloseableScope c = new ReactiveScope().enter()) {
				assertEquals("hi", l.get());
				assertTrue(CurrentReactiveScope.blocked());
			}
		}
		assertEquals(3, n.get());
	}
	@Test
	public void reactive() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveLazy<String> l = new ReactiveLazy<>(() -> v.get());
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			ReactiveScope s = new ReactiveScope();
			try (CloseableScope c = s.enter()) {
				assertEquals("hello", l.get());
				t.arm(s.versions());
			}
			assertFalse(t.fired());
			// Dependency invalidation is immediately propagated to dependent computations.
			v.set("hi");
			assertTrue(t.fired());
		}
	}
}
