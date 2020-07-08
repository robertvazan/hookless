// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.awaitility.Awaitility.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junitpioneer.jupiter.*;

public class ReactiveWorkerTest extends TestBase {
	@Test public void reactive() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		// Output of reactive worker reflects state of underlying dependencies.
		ReactiveWorker<String> w = new ReactiveWorker<>(v::get);
		await().ignoreExceptions().until(w::get, equalTo("hello"));
		// Changes in dependencies are reflected in worker state with a little delay.
		v.set("bye");
		await().until(w::get, equalTo("bye"));
	}
	@Test public void supplier() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		// Supplier can be provided after the constructor is called.
		ReactiveWorker<String> w = new ReactiveWorker<>();
		Supplier<String> s = v::get;
		w.supplier(s);
		await().ignoreExceptions().until(w::get, equalTo("hello"));
		// Supplier can be retrieved.
		assertSame(s, w.supplier());
		// It cannot be changed after the worker starts.
		assertThrows(IllegalStateException.class, () -> w.supplier(() -> "other"));
		// Supplier cannot be null.
		assertThrows(NullPointerException.class, () -> new ReactiveWorker<>(null));
		assertThrows(NullPointerException.class, () -> new ReactiveWorker<>().supplier(null));
		// Default supplier just returns null.
		ReactiveWorker<String> nw = new ReactiveWorker<>();
		await().ignoreExceptions().until(nw::get, nullValue());
		// If no supplier is set, the supply() method is called by default.
		ReactiveWorker<String> ow = new ReactiveWorker<String>() {
			@Override protected String supply() {
				// The supply() method can wrap configured supplier.
				return supplier().get() + "/bye";
			}
		};
		ow.supplier(v::get);
		await().ignoreExceptions().until(ow::get, equalTo("hello/bye"));
	}
	@Test public void exceptions() {
		ReactiveWorker<String> w = new ReactiveWorker<>(() -> {
			throw new ArithmeticException();
		});
		// Exceptions are propagated.
		await().untilAsserted(() -> {
			CompletionException ex = assertThrows(CompletionException.class, w::get);
			assertThat(ex.getCause(), instanceOf(ArithmeticException.class));
		});
	}
	@Test public void blocking() {
		// Blocking value is propagated as long as initial value is blocking (it is by default).
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("start", true));
		ReactiveWorker<String> w = new ReactiveWorker<>(v::get);
		await().until(() -> capture(w::get), equalTo(new ReactiveValue<>("start", true)));
		// Further blocking values are also propagated.
		v.value(new ReactiveValue<>("blocking", true));
		await().until(() -> capture(w::get), equalTo(new ReactiveValue<>("blocking", true)));
		// Blocking flag is cleared after first non-blocking computation.
		v.set("nonblocking");
		await().until(() -> capture(w::get), equalTo(new ReactiveValue<>("nonblocking")));
		// After the first non-blocking result, all future blocking results are ignored.
		v.value(new ReactiveValue<>("blocking again", true));
		settle();
		assertEquals(new ReactiveValue<>("nonblocking"), capture(w::get));
	}
	@RepeatFailedTest(10) public void initial() {
		Supplier<String> s = () -> {
			sleep(30);
			return "ready";
		};
		// By default, ReactiveBlockingException is thrown before first value is available.
		ReactiveWorker<String> w = new ReactiveWorker<>(s);
		ReactiveValue<String> iv = capture(w::get);
		assertTrue(iv.blocking());
		assertThat(iv.exception(), instanceOf(CompletionException.class));
		assertThat(iv.exception().getCause(), instanceOf(ReactiveBlockingException.class));
		// Initial value can be changed.
		w = new ReactiveWorker<>(s).initial(new ReactiveValue<>("initial", true));
		assertEquals(new ReactiveValue<>("initial", true), capture(w::get));
		// Initial value is blocking by default.
		w = new ReactiveWorker<>(s).initial("initial");
		assertEquals(new ReactiveValue<>("initial", true), capture(w::get));
		// Initial value cannot be changed once the worker is started.
		ReactiveWorker<String> tw = new ReactiveWorker<>(s);
		tw.initial("one");
		tw.get();
		assertThrows(IllegalStateException.class, () -> tw.initial("two"));
		// Non-blocking initial value will not be replaced by blocking computed value.
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("blocking", true));
		ReactiveWorker<String> nw = new ReactiveWorker<>(() -> {
			sleep(30);
			return v.get();
		});
		nw.initial(new ReactiveValue<>("initial"));
		assertEquals(new ReactiveValue<>("initial"), capture(nw::get));
		settle();
		assertEquals("initial", nw.get());
		// Non-blocking computed value will however replace any initial value.
		v.set("ready");
		await().until(() -> nw.get(), equalTo("ready"));
	}
	@Test public void equality() {
		// Equality checking is enabled by default.
		assertTrue(new ReactiveWorker<String>().equality());
		// Create equal but non-identical strings.
		String s1 = "hello";
		String s2 = new String(s1);
		// Disable equality on reactive variable to make sure changes are propagated to the worker.
		ReactiveVariable<String> v = new ReactiveVariable<>(s1);
		v.equality(false);
		// Worker will initially return the first string.
		ReactiveWorker<String> w = new ReactiveWorker<>(v::get);
		await().ignoreExceptions().until(() -> w.get(), sameInstance(s1));
		// When second string is provided, reactive variable returns it, but reactive worker sticks to the first string.
		v.set(s2);
		assertSame(s2, v.get());
		settle();
		assertSame(s1, w.get());
		// When equality checking is disabled, worker always returns the latest value.
		v.set(s1);
		ReactiveWorker<String> iw = new ReactiveWorker<>(v::get);
		iw.equality(false);
		await().ignoreExceptions().until(() -> iw.get(), sameInstance(s1));
		v.set(s2);
		await().forever().until(() -> iw.get(), sameInstance(s2));
		// Equality cannot be changed once the worker is started.
		assertThrows(IllegalStateException.class, () -> iw.equality(true));
	}
}
