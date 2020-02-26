// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

public class ReactiveVariableTest {
	@Test public void useAsAVariable() {
		// construct default
		ReactiveVariable<String> v = new ReactiveVariable<>();
		assertNull(v.get());
		// overwrite
		v.set("hello");
		assertEquals("hello", v.get());
		v.set("world");
		assertEquals("world", v.get());
		// construct non-default
		v = new ReactiveVariable<>("hi");
		assertEquals("hi", v.get());
	}
	@Test public void countChanges() {
		ReactiveVariable<String> v = new ReactiveVariable<>();
		assertEquals(1, v.version());
		v.set("hello");
		assertEquals(2, v.version());
		v.set("world");
		assertEquals(3, v.version());
	}
	@Test public void signalChange() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		AtomicInteger counter = new AtomicInteger(0);
		try (ReactiveTrigger trigger = new ReactiveTrigger()) {
			trigger.callback(counter::incrementAndGet);
			trigger.arm(Arrays.asList(v.new Version()));
			assertEquals(0, counter.get());
			v.set("hi");
			assertEquals(1, counter.get());
		}
	}
	@Test public void observeAccess() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		// observe access to the variable using ReactiveScope
		ReactiveScope scope = new ReactiveScope();
		try (ReactiveScope.Computation computation = scope.enter()) {
			assertEquals("hello", v.get());
		}
		// check that our variable has been observed
		assertSame(v, scope.versions().iterator().next().variable());
	}
	@Test public void storeException() {
		// set the variable to an exception rather than an object
		RuntimeException ex = new RuntimeException();
		ReactiveVariable<String> v = new ReactiveVariable<>();
		v.value(new ReactiveValue<String>(ex));
		// now check that the exception is being thrown
		try {
			// throws the supplied exception
			v.get();
			fail();
		} catch (CompletionException wrapper) {
			assertSame(ex, wrapper.getCause());
		}
	}
	@Test public void storeBlockingValue() {
		// set blocking value
		ReactiveVariable<String> v = new ReactiveVariable<>();
		v.value(new ReactiveValue<>("hello", true));
		// access it inside of a ReactiveScope
		ReactiveScope scope = new ReactiveScope();
		try (ReactiveScope.Computation computation = scope.enter()) {
			assertEquals("hello", v.get());
		}
		// the computation is reported as blocking
		assertTrue(scope.blocked());
	}
	@Test public void deduplicateWrites() {
		// create two objects that are equal but not the same
		String s1 = "hello";
		String s2 = new String(s1);
		// initialize with the first object
		ReactiveVariable<String> v = new ReactiveVariable<>(s1);
		assertEquals(1, v.version());
		// overwrite with the second, distinct but equal object
		v.set(s2);
		// as if the second write never happened
		assertEquals(1, v.version());
		assertSame(s1, v.get());
	}
	@Test public void disableEquality() {
		String s1 = "hello";
		String s2 = new String(s1);
		ReactiveVariable<String> v = new ReactiveVariable<>(s1);
		v.equality(false);
		assertEquals(1, v.version());
		v.set(s2);
		assertEquals(2, v.version());
	}
}
