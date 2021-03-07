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
	@Test
	public void reactive() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		// Output of reactive worker reflects state of underlying dependencies.
		ReactiveWorker<String> w = new ReactiveWorker<>(v::get);
		await().ignoreExceptions().until(w::get, equalTo("hello"));
		// Changes in dependencies are reflected in worker state with a little delay.
		v.set("bye");
		await().until(w::get, equalTo("bye"));
	}
	@Test
	public void supplier() {
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
			@Override
			protected String supply() {
				// The supply() method can wrap configured supplier.
				return supplier().get() + "/bye";
			}
		};
		ow.supplier(v::get);
		await().ignoreExceptions().until(ow::get, equalTo("hello/bye"));
	}
	@Test
	public void exceptions() {
		ReactiveWorker<String> w = new ReactiveWorker<>(() -> {
			throw new ArithmeticException();
		});
		// Exceptions are propagated.
		await().untilAsserted(() -> {
			CompletionException ex = assertThrows(CompletionException.class, w::get);
			assertThat(ex.getCause(), instanceOf(ArithmeticException.class));
		});
	}
	@Test
	public void blocking() {
		// Blocking value is propagated as long as initial value is blocking (it is by default).
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("start", true));
		ReactiveWorker<String> w = new ReactiveWorker<>(v::get);
		await().until(() -> ReactiveValue.capture(w::get), equalTo(new ReactiveValue<>("start", true)));
		// Further blocking values are also propagated.
		v.value(new ReactiveValue<>("blocking", true));
		await().until(() -> ReactiveValue.capture(w::get), equalTo(new ReactiveValue<>("blocking", true)));
		// Blocking flag is cleared after first non-blocking computation.
		v.set("nonblocking");
		await().until(() -> ReactiveValue.capture(w::get), equalTo(new ReactiveValue<>("nonblocking")));
		// After the first non-blocking result, all future blocking results are ignored.
		v.value(new ReactiveValue<>("blocking again", true));
		settle();
		assertEquals(new ReactiveValue<>("nonblocking"), ReactiveValue.capture(w::get));
	}
	@RetryingTest(10)
	public void initial() {
		Supplier<String> s = () -> {
			sleep(30);
			return "ready";
		};
		// By default, ReactiveBlockingException is thrown before first value is available.
		ReactiveWorker<String> w = new ReactiveWorker<>(s);
		ReactiveValue<String> iv = ReactiveValue.capture(w::get);
		assertTrue(iv.blocking());
		assertThat(iv.exception(), instanceOf(CompletionException.class));
		assertThat(iv.exception().getCause(), instanceOf(ReactiveBlockingException.class));
		// Initial value can be changed.
		w = new ReactiveWorker<>(s).initial(new ReactiveValue<>("initial", true));
		assertEquals(new ReactiveValue<>("initial", true), ReactiveValue.capture(w::get));
		// Initial value is blocking by default.
		w = new ReactiveWorker<>(s).initial("initial");
		assertEquals(new ReactiveValue<>("initial", true), ReactiveValue.capture(w::get));
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
		assertEquals(new ReactiveValue<>("initial"), ReactiveValue.capture(nw::get));
		settle();
		assertEquals("initial", nw.get());
		// Non-blocking computed value will however replace any initial value.
		v.set("ready");
		await().until(() -> nw.get(), equalTo("ready"));
	}
	@Test
	public void equality() {
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
	@Test
	public void pause() {
		// Consider an expensive busy-looping worker.
		ReactiveVariable<Long> v = new ReactiveVariable<>(0L);
		ReactiveWorker<Long> w = new ReactiveWorker<>(() -> {
			v.set(v.get() + 1);
			return v.get();
		});
		w.initial(0L);
		// Keep it alive by referencing it from a reactive thread.
		ReactiveThread t = new ReactiveThread(() -> w.get());
		t.start();
		try {
			// Worker keeps running.
			await().until(v::get, greaterThan(100L));
		} finally {
			t.stop();
		}
		// When the thread is stopped and the worker is no longer queried, it ceases to execute.
		settle();
		long n = v.get();
		settle();
		assertEquals(n, v.get());
		// The next value read is marked as blocking, because the worker stopped updating it.
		assertEquals(new ReactiveValue<>(n, true), ReactiveValue.capture(w::get));
		// Worker starts again and generates non-blocking output.
		await().until(() -> !ReactiveValue.capture(w::get).blocking());
	}
	@Test
	public void executor() {
		ReactiveWorker<ReactiveExecutor> w = new ReactiveWorker<>(() -> ReactiveExecutor.current());
		// Common reactive executor is used by default.
		assertSame(ReactiveExecutor.common(), w.executor());
		ReactiveExecutor x = new ReactiveExecutor();
		// Custom executor can be set.
		w.executor(x);
		assertSame(x, w.executor());
		// Worker runs on the executor.
		await().ignoreExceptions().until(w::get, sameInstance(x));
		x.shutdown();
	}
}
