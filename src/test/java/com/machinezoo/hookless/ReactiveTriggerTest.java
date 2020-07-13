// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

public class ReactiveTriggerTest {
	@Test
	public void states() {
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			// Initial state.
			assertFalse(t.armed());
			assertFalse(t.fired());
			assertFalse(t.closed());
			// Armed state.
			t.arm(Collections.emptyList());
			assertTrue(t.armed());
			assertFalse(t.fired());
			assertFalse(t.closed());
			// Fired state.
			t.fire();
			assertTrue(t.armed());
			assertTrue(t.fired());
			assertFalse(t.closed());
			// Closed state.
			t.close();
			assertTrue(t.armed());
			assertTrue(t.fired());
			assertTrue(t.closed());
		}
	}
	@Test
	public void callback() {
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			AtomicInteger n = new AtomicInteger(0);
			t.callback(n::incrementAndGet);
			t.arm(Collections.emptyList());
			// No change has been signaled so far.
			assertEquals(0, n.get());
			// Firing the trigger causes the callback to be invoked immediately.
			t.fire();
			assertEquals(1, n.get());
			// Firing second time has no effect.
			t.fire();
			assertEquals(1, n.get());
		}
	}
	@Test
	public void fireOnVariableChange() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		AtomicInteger n = new AtomicInteger(0);
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.callback(n::incrementAndGet);
			t.arm(Arrays.asList(new ReactiveVariable.Version(v)));
			// No change has been signaled so far.
			assertFalse(t.fired());
			assertEquals(0, n.get());
			// Variable write fires the trigger.
			v.set("hi");
			assertTrue(t.fired());
			assertEquals(1, n.get());
		}
	}
	@Test
	public void closeAtAnyTime() {
		AtomicInteger n = new AtomicInteger(0);
		// Closing before arming disables arming and causes firing to be ignored.
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.callback(n::incrementAndGet);
			t.close();
			assertFalse(t.armed());
			assertFalse(t.fired());
			assertTrue(t.closed());
			assertThrows(Throwable.class, () -> t.arm(Collections.emptyList()));
			t.fire();
			assertFalse(t.fired());
			assertEquals(0, n.get());
		}
		// Closing before firing causes firing to be ignored.
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.callback(n::incrementAndGet);
			t.arm(Collections.emptyList());
			t.close();
			assertTrue(t.armed());
			assertFalse(t.fired());
			assertTrue(t.closed());
			t.fire();
			assertFalse(t.fired());
			assertEquals(0, n.get());
		}
	}
	@Test
	public void watchManyVariables() {
		ReactiveVariable<String> v1 = new ReactiveVariable<>("a");
		ReactiveVariable<String> v2 = new ReactiveVariable<>("b");
		ReactiveVariable<String> v3 = new ReactiveVariable<>("c");
		AtomicInteger n = new AtomicInteger(0);
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.callback(n::incrementAndGet);
			t.arm(Arrays.asList(new ReactiveVariable.Version(v1), new ReactiveVariable.Version(v2), new ReactiveVariable.Version(v3)));
			assertFalse(t.fired());
			// First variable to change fires the trigger.
			v2.set("hi");
			assertTrue(t.fired());
			assertEquals(1, n.get());
			// Subsequent variable changes have no effect.
			v3.set("hello");
			assertEquals(1, n.get());
		}
	}
	@Test
	public void fireImmediately() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		AtomicInteger n = new AtomicInteger(0);
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.callback(n::incrementAndGet);
			// Arming with an old version causes the trigger to fire immediately.
			t.arm(Arrays.asList(new ReactiveVariable.Version(v, v.version() - 1)));
			assertTrue(t.fired());
			assertEquals(1, n.get());
			// Fire, if called anyway, then has no effect.
			t.fire();
			assertEquals(1, n.get());
		}
	}
	@Test
	public void fireUnarmed() {
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			// It is possible to fire an unarmed trigger, but it probably isn't useful.
			t.fire();
			assertTrue(t.fired());
		}
	}
}
