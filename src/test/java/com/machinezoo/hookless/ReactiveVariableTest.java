// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import com.machinezoo.closeablescope.*;

public class ReactiveVariableTest {
	@Test
	public void crud() {
		// Construct default.
		ReactiveVariable<String> v = new ReactiveVariable<>();
		assertNull(v.get());
		// Write and read.
		v.set("hello");
		assertEquals("hello", v.get());
		v.set("world");
		assertEquals("world", v.get());
		// Construct non-default.
		v = new ReactiveVariable<>("hi");
		assertEquals("hi", v.get());
		// Allow nulls.
		v.set(null);
		assertNull(v.get());
	}
	@Test
	public void versions() {
		// Versions are incremented by one after every write. First version is 1.
		ReactiveVariable<String> v = new ReactiveVariable<>();
		assertEquals(1, v.version());
		v.set("hello");
		assertEquals(2, v.version());
		v.set("world");
		assertEquals(3, v.version());
	}
	@Test
	public void fireOnChange() {
		// The only way to listen to changes in the variable is to setup a trigger.
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		AtomicInteger c = new AtomicInteger();
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			// Trigger can then invoke any callback.
			t.callback(c::incrementAndGet);
			t.arm(Arrays.asList(new ReactiveVariable.Version(v)));
			// The trigger didn't fire yet.
			assertEquals(0, c.get());
			// First change fires the trigger.
			v.set("hi");
			assertEquals(1, c.get());
			// Further changes have no effect on the same trigger.
			v.set("stop");
			assertEquals(1, c.get());
		}
	}
	@Test
	public void trackAccess() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		// Use ReactiveScope to detect variable access.
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			assertEquals("hello", v.get());
			// Variable access has been observed.
			assertSame(v, s.versions().stream().findFirst().get().variable());
		}
	}
	@Test
	public void storeReactiveValue() {
		// Write and read the value.
		assertEquals(new ReactiveValue<>("value"), new ReactiveVariable<>(new ReactiveValue<>("value")).value());
		// Store ordinary value via ReactiveValue.
		assertEquals("value", new ReactiveVariable<>(new ReactiveValue<>("value")).get());
		// Store exception.
		RuntimeException ex = new RuntimeException();
		CompletionException ce = assertThrows(CompletionException.class, () -> new ReactiveVariable<>(new ReactiveValue<>(ex)).get());
		assertSame(ex, ce.getCause());
		// Store and propagate blocking flag.
		ReactiveScope s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			assertEquals("value", new ReactiveVariable<>(new ReactiveValue<>("value", true)).get());
			assertTrue(s.blocked());
		}
		// Fire trigger when assigning ReactiveValue.
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			AtomicInteger c = new AtomicInteger();
			t.callback(c::incrementAndGet);
			ReactiveVariable<String> v = new ReactiveVariable<>();
			t.arm(Arrays.asList(new ReactiveVariable.Version(v)));
			v.value(new ReactiveValue<>("hi"));
			assertEquals(1, c.get());
		}
		// Track dependency when reading the variable as a ReactiveValue.
		s = new ReactiveScope();
		try (CloseableScope c = s.enter()) {
			ReactiveVariable<String> v = new ReactiveVariable<>("value");
			assertEquals(new ReactiveValue<>("value"), v.value());
			assertSame(v, s.versions().stream().findFirst().get().variable());
		}
	}
	@Test
	public void deduplicateWrites() {
		String s = "hello";
		ReactiveVariable<String> v = new ReactiveVariable<>(s);
		assertEquals(1, v.version());
		// Overwrite with value that is equal but not the same.
		v.set(new String(s));
		// Variable behaves as if the second write never happened.
		assertEquals(1, v.version());
		assertSame(s, v.get());
	}
	@Test
	public void disableEquality() {
		String s1 = "hello";
		String s2 = new String(s1);
		ReactiveVariable<String> v = new ReactiveVariable<>(s1);
		v.equality(false);
		assertEquals(1, v.version());
		// When value equality checking is disabled, the variable can be overwritten with object that is equal but not the same.
		v.set(s2);
		assertEquals(2, v.version());
		assertSame(s2, v.get());
		// But writing the same (reference-equal) object doesn't change anything, because reference equality is still checked.
		v.set(s2);
		assertEquals(2, v.version());
	}
}
