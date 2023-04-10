// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.awaitility.Awaitility.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import com.machinezoo.closeablescope.*;

public class ReactiveFutureTest extends TestBase {
	@Test
	public void wrap() {
		// Any CompletableFuture can be wrapped.
		CompletableFuture<String> cf = new CompletableFuture<>();
		ReactiveFuture<String> rf = ReactiveFuture.wrap(cf);
		assertSame(cf, rf.completable());
		// Wrapping the same CompletableFuture always returns the same ReactiveFuture.
		assertSame(rf, ReactiveFuture.wrap(cf));
	}
	@Test
	public void create() {
		// ReactiveFuture can also construct its own CompletableFuture if none is provided explicitly.
		ReactiveFuture<String> rf = new ReactiveFuture<>();
		assertNotNull(rf.completable());
		// Wrapping the CompletableFuture just returns the ReactiveFuture that created it.
		assertSame(rf, ReactiveFuture.wrap(rf.completable()));
	}
	@Test
	public void waiting() {
		ReactiveFuture<String> rf = new ReactiveFuture<>();
		try (CloseableScope c = new ReactiveScope().enter()) {
			// State checks are negative without any blocking.
			assertFalse(rf.done());
			assertFalse(rf.failed());
			assertFalse(rf.cancelled());
			// When fallback is provided, it is returned without blocking.
			assertEquals("fallback", rf.getNow("fallback"));
			assertFalse(CurrentReactiveScope.blocked());
			// Without fallback, blocking exception is thrown.
			assertThrows(ReactiveBlockingException.class, () -> rf.get());
			assertTrue(CurrentReactiveScope.blocked());
		}
	}
	@Test
	public void done() {
		ReactiveFuture<String> rf = ReactiveFuture.wrap(CompletableFuture.completedFuture("hello"));
		try (CloseableScope c = new ReactiveScope().enter()) {
			assertTrue(rf.done());
			assertFalse(rf.failed());
			assertFalse(rf.cancelled());
			assertEquals("hello", rf.getNow("fallback"));
			assertEquals("hello", rf.get());
			// No blocking in any of these.
			assertFalse(CurrentReactiveScope.blocked());
		}
	}
	private static <T extends Throwable> void assertThrowsWrapped(Class<T> clazz, Runnable runnable) {
		CompletionException ce = assertThrows(CompletionException.class, runnable::run);
		assertThat(ce.getCause(), instanceOf(clazz));
	}
	@Test
	public void failed() {
		ReactiveFuture<String> rf = new ReactiveFuture<>();
		rf.completable().completeExceptionally(new ArithmeticException());
		try (CloseableScope c = new ReactiveScope().enter()) {
			assertTrue(rf.done());
			assertTrue(rf.failed());
			assertFalse(rf.cancelled());
			assertThrowsWrapped(ArithmeticException.class, () -> rf.getNow("fallback"));
			assertThrowsWrapped(ArithmeticException.class, () -> rf.get());
			// No blocking in any of these.
			assertFalse(CurrentReactiveScope.blocked());
		}
	}
	@Test
	public void cancelled() {
		ReactiveFuture<String> rf = new ReactiveFuture<>();
		rf.completable().cancel(false);
		try (CloseableScope c = new ReactiveScope().enter()) {
			assertTrue(rf.done());
			assertTrue(rf.failed());
			assertTrue(rf.cancelled());
			assertThrows(CancellationException.class, () -> rf.getNow("fallback"));
			assertThrows(CancellationException.class, () -> rf.get());
			// No blocking in any of these.
			assertFalse(CurrentReactiveScope.blocked());
		}
	}
	public static Stream<Object> completers() {
		return Stream.of(
			Arguments.of("done", (Consumer<CompletableFuture<String>>)(f -> f.complete("hello"))),
			Arguments.of("failed", (Consumer<CompletableFuture<String>>)(f -> f.completeExceptionally(new ArithmeticException()))),
			Arguments.of("cancelled", (Consumer<CompletableFuture<String>>)(f -> f.cancel(false))));
	}
	@ParameterizedTest
	@MethodSource("completers")
	public void reactive(String name, Consumer<CompletableFuture<String>> completer) {
		ReactiveFuture<String> rf = new ReactiveFuture<>();
		// Watch all methods of the reactive future.
		List<ReactiveStateMachine<?>> sms = new ArrayList<>();
		sms.add(ReactiveStateMachine.supply(() -> rf.done()));
		sms.add(ReactiveStateMachine.supply(() -> rf.failed()));
		sms.add(ReactiveStateMachine.supply(() -> rf.cancelled()));
		sms.add(ReactiveStateMachine.supply(() -> rf.get()));
		sms.add(ReactiveStateMachine.supply(() -> rf.getNow("fallback")));
		for (ReactiveStateMachine<?> sm : sms) {
			sm.advance();
			assertTrue(sm.valid());
		}
		// When the reactive future is completed (in any way), all methods signal change.
		completer.accept(rf.completable());
		for (ReactiveStateMachine<?> sm : sms) {
			assertFalse(sm.valid());
			sm.advance();
			assertTrue(sm.valid());
		}
		// Redundant second completion has no effect and no change is signaled.
		completer.accept(rf.completable());
		for (ReactiveStateMachine<?> sm : sms)
			assertTrue(sm.valid());
	}
	@Test
	public void supplyReactive() {
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("pending", true));
		CompletableFuture<String> f = ReactiveFuture.supplyReactive(v::get);
		// The future is not completed when the supplier is blocking.
		settle();
		assertFalse(f.isDone());
		// Non-blocking result will be stored in the future.
		v.set("done");
		await().until(f::isDone);
		assertEquals("done", f.join());
		// Further changes have no effect on the future.
		v.set("extra");
		settle();
		assertEquals("done", f.join());
		// It works the same way with exceptions.
		v.value(new ReactiveValue<>(new ReactiveBlockingException(), true));
		f = ReactiveFuture.supplyReactive(v::get);
		settle();
		assertFalse(f.isDone());
		v.value(new ReactiveValue<>(new ArithmeticException()));
		await().until(f::isDone);
		assertTrue(f.isCompletedExceptionally());
		ExecutionException ex = assertThrows(ExecutionException.class, f::get);
		assertThat(ex.getCause(), instanceOf(ArithmeticException.class));
	}
	@Test
	public void runReactive() {
		AtomicInteger n = new AtomicInteger();
		ReactiveVariable<String> v = new ReactiveVariable<>(new ReactiveValue<>("pending", true));
		CompletableFuture<Void> f = ReactiveFuture.runReactive(() -> {
			v.get();
			n.incrementAndGet();
		});
		// Runnable runs, but the future is not completed, because the Runnable is blocking.
		await().untilAtomic(n, equalTo(1));
		settle();
		assertFalse(f.isDone());
		// The first non-blocking run completes the future.
		v.set("done");
		await().untilAtomic(n, equalTo(2));
		await().until(f::isDone);
		// Further changes in dependencies do not cause the Runnable to run again.
		v.set("extra");
		settle();
		assertEquals(2, n.get());
	}
	@Test
	public void supplyReactiveExecutor() {
		ReactiveExecutor x = new ReactiveExecutor();
		// Custom executor can be specified.
		CompletableFuture<ReactiveExecutor> f = ReactiveFuture.supplyReactive(() -> ReactiveExecutor.current(), x);
		// Supplier runs on the executor.
		assertSame(x, f.join());
		x.shutdown();
	}
	@Test
	public void runReactiveExecutor() {
		AtomicReference<ReactiveExecutor> cx = new AtomicReference<>();
		ReactiveExecutor x = new ReactiveExecutor();
		// Custom executor can be specified.
		ReactiveFuture.runReactive(() -> cx.set(ReactiveExecutor.current()), x).join();
		// Runnable runs on the executor.
		assertSame(x, cx.get());
		x.shutdown();
	}
}
