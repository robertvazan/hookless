// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.awaitility.Awaitility.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

public class ReactiveThreadTest extends TestBase {
	ReactiveThread t = new ReactiveThread();
	@AfterEach
	public void stop() {
		t.stop();
	}
	@Test
	public void reactive() {
		AtomicInteger n = new AtomicInteger();
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		t = new ReactiveThread(() -> {
			v.get();
			n.incrementAndGet();
		});
		// No action is taken before the thread is explicitly started.
		assertEquals(0, n.get());
		// When started, the thread runs once.
		t.start();
		await().untilAtomic(n, equalTo(1));
		// It then runs whenever its reactive dependencies change.
		v.set("hi");
		await().untilAtomic(n, equalTo(2));
		v.set("bye");
		await().untilAtomic(n, equalTo(3));
	}
	@Test
	public void runnable() {
		AtomicInteger n = new AtomicInteger();
		// Thread's Runnable can be also supplied after constructor is called.
		t.runnable(n::incrementAndGet);
		assertEquals(0, n.get());
		t.start();
		await().untilAtomic(n, equalTo(1));
		// It however cannot be changed after the thread is started.
		assertThrows(IllegalStateException.class, () -> t.runnable(n::decrementAndGet));
		// Runnable must be non-null.
		assertThrows(NullPointerException.class, () -> new ReactiveThread(null));
		assertThrows(NullPointerException.class, () -> new ReactiveThread().runnable(null));
	}
	@Test
	public void overridable() {
		AtomicInteger n = new AtomicInteger();
		// If no Runnable is provided, thread's run() method is called.
		t = new ReactiveThread() {
			@Override
			protected void run() {
				n.incrementAndGet();
			}
		};
		t.start();
		await().untilAtomic(n, equalTo(1));
	}
	@Test
	public void current() {
		assertNull(ReactiveThread.current());
		AtomicInteger n = new AtomicInteger();
		t.runnable(() -> {
			assertEquals(t, ReactiveThread.current());
			// Increment to signal that the above assertion passed.
			n.incrementAndGet();
		});
		t.start();
		await().untilAtomic(n, equalTo(1));
		assertNull(ReactiveThread.current());
	}
	@Test
	public void stoppable() {
		AtomicInteger n = new AtomicInteger();
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		t = new ReactiveThread(() -> {
			v.get();
			n.incrementAndGet();
			// Thread can be stopped from within its Runnable.
			ReactiveThread.current().stop();
		});
		t.start();
		await().untilAtomic(n, equalTo(1));
		// Once the thread is stopped, it will not run in response to dependency changes.
		v.set("hi");
		settle();
		assertEquals(1, n.get());
	}
	@Test
	public void states() {
		AtomicInteger n = new AtomicInteger();
		t = new ReactiveThread(n::incrementAndGet);
		// It is safe to start the thread twice.
		t.start();
		t.start();
		await().untilAtomic(n, equalTo(1));
		// It is safe to stop the thread twice.
		t.stop();
		t.stop();
		// Thread cannot be restarted, but since it is allowed to stop the thread before it is started, restart attempts are silently ignored. 
		t.start();
		settle();
		assertEquals(1, n.get());
		// It is allowed to stop the thread before it is started. In that case, starting the thread has no effect.
		t = new ReactiveThread(n::incrementAndGet);
		t.stop();
		t.start();
		settle();
		assertEquals(1, n.get());
	}
	@Test
	public void pinning() {
		AtomicInteger n = new AtomicInteger();
		ReactiveVariable<String> o = new ReactiveVariable<>();
		Map<String, ReactiveVariable<String>> m = new HashMap<>();
		m.put("a", new ReactiveVariable<>("b"));
		ReactiveValue<String> bv = new ReactiveValue<>(new ReactiveBlockingException(), true);
		m.put("b", new ReactiveVariable<>(bv));
		t.runnable(() -> {
			try {
				ReactiveVariable<String> a = m.get("a");
				String p = CurrentReactiveScope.pin("pinkey", () -> a.get());
				ReactiveVariable<String> b = m.get(p);
				o.set(b.get());
			} finally {
				n.incrementAndGet();
			}
		});
		t.start();
		// Initial run will not write the result due to blocking.
		await().untilAtomic(n, equalTo(1));
		assertNull(o.get());
		// Thread will run again due to dependency change, but its behavior will not change since the value was already pinned.
		m.put("c", new ReactiveVariable<>(bv));
		m.get("a").set("c");
		await().untilAtomic(n, equalTo(2));
		assertNull(o.get());
		// Final result is then based on the pinned value. There will be two iteration due to the way blocking interacts with pinning.
		m.get("b").set("hello");
		await().untilAtomic(n, equalTo(4));
		assertEquals("hello", o.get());
		// Pinned value is then discarded and pinning is done anew.
		m.put("d", new ReactiveVariable<>(bv));
		m.get("a").set("d");
		await().untilAtomic(n, equalTo(5));
		assertEquals("hello", o.get());
		m.get("c").set("bye");
		await().untilAtomic(n, equalTo(7));
		assertEquals("bye", o.get());
	}
	@Test
	public void blockingException() {
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>(new ReactiveBlockingException(), true));
		ReactiveVariable<String> o = new ReactiveVariable<>();
		t.runnable(() -> o.set(v.get()));
		t.handler((rt, ex) -> o.set("exception"));
		// Blocking exception is silently ignored.
		t.start();
		settle();
		assertNull(o.get());
		// Reactive thread continues to run when dependencies change.
		v.set("hello");
		await().until(o::get, equalTo("hello"));
	}
	@Test
	public void nonblockingException() {
		ReactiveVariable<String> v = new ReactiveVariable<>("initial");
		ReactiveVariable<String> o = new ReactiveVariable<>();
		t.runnable(() -> o.set(v.get()));
		t.handler((rt, ex) -> o.set("handled"));
		t.start();
		await().until(o::get, equalTo("initial"));
		// Non-blocking exception causes the uncaught exception handler to be called.
		v.value(new ReactiveValue<>(new NumberFormatException()));
		await().until(o::get, equalTo("handled"));
		// Thread is stopped. Dependency changes don't cause the thread to run again.
		v.set("bye");
		settle();
		assertEquals("handled", o.get());
	}
	volatile Object pressure;
	@Test
	public void daemon() {
		ReactiveVariable<String> v = new ReactiveVariable<>("hello");
		ReactiveVariable<String> o = new ReactiveVariable<>();
		// Daemon threads run normally like non-daemon threads as long as they are referenced.
		ReactiveThread dt = new ReactiveThread(() -> o.set(v.get()))
			.daemon(true)
			.start();
		await().until(o::get, equalTo("hello"));
		// Once the last strong reference is gone, daemon threads may be collected.
		WeakReference<?> w = new WeakReference<>(dt);
		dt = null;
		// Increase pressure on GC until the thread is collected.
		while (w.get() != null)
			pressure = Arrays.asList(pressure);
	}
	@Test
	public void executor() {
		AtomicReference<ReactiveExecutor> cx = new AtomicReference<>();
		ReactiveThread t = new ReactiveThread(() -> cx.set(ReactiveExecutor.current()));
		// Common reactive executor is used by default.
		assertSame(ReactiveExecutor.common(), t.executor());
		ReactiveExecutor x = new ReactiveExecutor();
		// Custom executor can be set.
		t.executor(x);
		assertSame(x, t.executor());
		t.start();
		// Thread runs on the executor.
		await().untilAtomic(cx, sameInstance(x));
		x.shutdown();
	}
}
