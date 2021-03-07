// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.awaitility.Awaitility.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junitpioneer.jupiter.*;

public class ReactiveExecutorTest extends TestBase {
	ReactiveExecutor x;
	@BeforeEach
	public void setup() {
		x = new ReactiveExecutor();
	}
	@AfterEach
	public void cleanup() throws Exception {
		x.shutdown();
		x.awaitTermination(1, TimeUnit.MINUTES);
	}
	@Test
	public void submit() throws Exception {
		AtomicInteger n = new AtomicInteger();
		// Task submission works as usual.
		x.execute(() -> n.incrementAndGet());
		Future<?> fr = x.submit(() -> {
			n.incrementAndGet();
		});
		Future<String> fc = x.submit(() -> {
			n.incrementAndGet();
			return "done";
		});
		Future<String> fv = x.submit(() -> {
			n.incrementAndGet();
		}, "ok");
		// All tasks are executed.
		await().untilAtomic(n, equalTo(4));
		// Results are propagated into futures.
		fr.get();
		assertEquals("done", fc.get());
		assertEquals("ok", fv.get());
	}
	// Simulation of computation.
	private void waste(Duration duration) {
		long nanos = duration.toNanos();
		long start = System.nanoTime();
		while (System.nanoTime() - start < nanos)
			;
	}
	@Test
	public void countSequential() {
		// Counters start at zero.
		assertEquals(0, x.getTaskCount());
		assertEquals(0, x.getEventCount());
		AtomicInteger n = new AtomicInteger();
		x.execute(() -> n.incrementAndGet());
		await().untilAtomic(n, equalTo(1));
		// Task counter is incremented every time some task is executed.
		assertEquals(1, x.getTaskCount());
		// Without any queuing, event counter is identical to task counter.
		assertEquals(1, x.getEventCount());
		x.execute(() -> n.incrementAndGet());
		await().untilAtomic(n, equalTo(2));
		assertEquals(2, x.getTaskCount());
		assertEquals(2, x.getEventCount());
	}
	@Test
	public void countParallel() {
		// Run 100 tasks per core, each 1ms long.
		AtomicInteger n = new AtomicInteger();
		int tc = 100 * Runtime.getRuntime().availableProcessors();
		for (int i = 0; i < tc; ++i) {
			x.execute(() -> {
				waste(Duration.ofMillis(1));
				n.incrementAndGet();
			});
		}
		await().untilAtomic(n, equalTo(tc));
		// Task count is incremented for every executed task.
		assertEquals(tc, x.getTaskCount());
		// But event count is much smaller, because queued tasks are aggregated in events.
		assertThat(x.getEventCount(), lessThan(tc / 20L));
	}
	@RetryingTest(10)
	public void parallelism() {
		// Submit 300ms worth of 10ms tasks.
		AtomicInteger n = new AtomicInteger();
		int tc = 30 * Runtime.getRuntime().availableProcessors();
		long t0 = System.nanoTime();
		for (int i = 0; i < tc; ++i) {
			x.execute(() -> {
				waste(Duration.ofMillis(10));
				n.incrementAndGet();
			});
		}
		await().untilAtomic(n, equalTo(tc));
		// Expect them to complete in 150% of the minimum time, which proves they run in parallel.
		assertThat(Duration.ofNanos(System.nanoTime() - t0).toMillis(), lessThan(450L));
	}
	// Simulation of cascading tasks, each taking 5ms.
	private void cascade(int depth, Runnable then) {
		waste(Duration.ofMillis(5));
		if (depth <= 1)
			then.run();
		else
			x.execute(() -> cascade(depth - 1, then));
	}
	@RetryingTest(10)
	public void latency() throws Exception {
		// Start 150ms 30-task cascade. This coincides with executor's maximum cascade depth of 30.
		AtomicReference<Duration> latency = new AtomicReference<>();
		long t0 = System.nanoTime();
		x.execute(() -> cascade(30, () -> latency.set(Duration.ofNanos(System.nanoTime() - t0))));
		// Make sure the cascade has started, i.e. the executor has advanced its event counter.
		await().until(() -> x.getEventCount() > 0);
		// The cascade has not completed yet.
		assertNull(latency.get());
		// Swamp the executor with 500ms worth of work.
		int tc = 50 * Runtime.getRuntime().availableProcessors();
		for (int i = 0; i < tc; ++i)
			x.execute(() -> waste(Duration.ofMillis(10)));
		// Latency of the cascading task remains low.
		await().untilAtomic(latency, notNullValue());
		long ms = latency.get().toMillis();
		assertThat(ms, greaterThan(150L));
		assertThat(ms, lessThan(225L));
	}
	@Test
	public void current() throws Exception {
		assertSame(x, x.submit(() -> ReactiveExecutor.current()).get());
	}
}
