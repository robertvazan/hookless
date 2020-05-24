// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static java.util.stream.Collectors.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

public class ReactiveScopeTest {
	@Test public void observeVariableAccess() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			assertEquals("hello", v.get());
		}
		assertThat(s.versions().stream().map(x -> x.variable()).collect(toList()), contains(v));
	}
	@Test public void nest() {
		ReactiveVariable<String> v1 = new ReactiveVariable<>("hello");
		ReactiveVariable<String> v2 = new ReactiveVariable<>("world");
		ReactiveScope s1 = new ReactiveScope();
		ReactiveScope s2 = new ReactiveScope();
		try (ReactiveScope.Computation c1 = s1.enter()) {
			assertEquals("hello", v1.get());
			try (ReactiveScope.Computation c2 = s2.enter()) {
				assertEquals("world", v2.get());
			}
		}
		assertThat(s1.versions().stream().map(v -> v.variable()).collect(toList()), contains(v1));
		assertThat(s2.versions().stream().map(v -> v.variable()).collect(toList()), contains(v2));
	}
	@Test public void current() {
		assertNull(ReactiveScope.current());
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			assertSame(s, ReactiveScope.current());
		}
		assertNull(ReactiveScope.current());
	}
	@Test public void keepFirstVersion() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			assertEquals("hello", v.get());
			v.set("world");
			assertEquals("world", v.get());
		}
		assertEquals(v.version() - 1, s.versions().stream().findFirst().get().number());
	}
	@Test public void pickEarlierVersion() {
		// create a variable with lots of versions
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		for (int i = 0; i < 10; ++i)
			v.set("world " + i);
		// simulate collection of unordered versions, e.g. from multiple threads
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			s.watch(v, 3);
			s.watch(v, 2);
			s.watch(v, 4);
		}
		// earliest version is kept
		assertEquals(2, s.versions().stream().findFirst().get().number());
	}
	@Test public void ignore() {
		ReactiveVariable<String> v1 = new ReactiveVariable<>("hello");
		ReactiveVariable<String> v2 = new ReactiveVariable<>("world");
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c1 = s.enter()) {
			assertEquals("hello", v1.get());
			try (ReactiveScope.Computation c2 = ReactiveScope.ignore()) {
				assertEquals("world", v2.get());
			}
		}
		assertThat(s.versions().stream().map(v -> v.variable()).collect(toList()), contains(v1));
	}
	@Test public void block() {
		ReactiveScope s = new ReactiveScope();
		assertFalse(s.blocked());
		try (ReactiveScope.Computation c = s.enter()) {
			CurrentReactiveScope.block();
		}
		assertTrue(s.blocked());
	}
	@Test public void nonblocking() {
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c1 = s.enter()) {
			try (ReactiveScope.Computation c2 = ReactiveScope.nonblocking()) {
				CurrentReactiveScope.block();
			}
		}
		assertFalse(s.blocked());
	}
	@Test public void freeze() {
		ReactiveScope s = new ReactiveScope();
		try (ReactiveScope.Computation c = s.enter()) {
			String[] values = new String[2];
			for (int i = 0; i < 2; ++i) {
				// Use String constructor to force instances to be unique.
				values[i] = CurrentReactiveScope.freeze("key", () -> new String("hello"));
			}
			assertEquals("hello", values[0]);
			assertSame(values[0], values[1]);
		}
		assertEquals(new HashSet<>(Arrays.asList("key")), s.freezes().keys());
		assertEquals("hello", s.freezes().get("key").result());
	}
	@Test public void pin() {
		ReactiveScope s1 = new ReactiveScope();
		String pinned;
		try (ReactiveScope.Computation c1 = s1.enter()) {
			// use String constructor to ensure we get a new instance each time
			pinned = CurrentReactiveScope.pin("key", () -> new String("value"));
		}
		// transfer the draft to the next scope
		ReactiveScope s2 = new ReactiveScope();
		s2.pins(s1.pins());
		// expect to get the same object back
		try (ReactiveScope.Computation c1 = s2.enter()) {
			assertSame(pinned, CurrentReactiveScope.pin("key", () -> new String("value")));
		}
	}
}
