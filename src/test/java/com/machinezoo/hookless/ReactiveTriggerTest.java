package com.machinezoo.hookless;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

public class ReactiveTriggerTest {
	@Test public void signalChange() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		AtomicInteger n = new AtomicInteger(0);
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.callback(n::incrementAndGet);
			t.arm(Arrays.asList(v.new Version()));
			assertEquals(0, n.get());
			v.set("hi");
			assertEquals(1, n.get());
		}
	}
	@Test public void observableState() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			assertFalse(t.armed());
			t.arm(Arrays.asList(v.new Version()));
			assertTrue(t.armed());
			assertFalse(t.fired());
			v.set("hi");
			assertTrue(t.fired());
			assertFalse(t.closed());
			t.close();
			assertTrue(t.closed());
		}
	}
	@Test public void watchManyVariables() {
		ReactiveVariable<String> v1 = new ReactiveVariable<>("a");
		ReactiveVariable<String> v2 = new ReactiveVariable<>("b");
		ReactiveVariable<String> v3 = new ReactiveVariable<>("c");
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.arm(Arrays.asList(v1.new Version(), v2.new Version(), v3.new Version()));
			assertFalse(t.fired());
			v2.set("hi");
			assertTrue(t.fired());
		}
	}
	@Test public void fireImmediately() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		AtomicInteger n = new AtomicInteger(0);
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.callback(n::incrementAndGet);
			t.arm(Arrays.asList(v.new Version(v.version() - 1)));
			assertTrue(t.fired());
			assertEquals(1, n.get());
		}
	}
	@Test public void tolerateDoubleFireAndClose() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.arm(Arrays.asList(v.new Version()));
			t.fire();
			t.fire();
			t.close();
		}
	}
	@Test public void fireUnarmed() {
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.fire();
			assertTrue(t.fired());
		}
	}
	@Test public void closeAtAnyTime() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.close();
			assertTrue(t.closed());
		}
		try (ReactiveTrigger t = new ReactiveTrigger()) {
			t.arm(Arrays.asList(v.new Version()));
			t.close();
			assertTrue(t.closed());
		}
	}
}
