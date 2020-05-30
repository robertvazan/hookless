// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

public class ReactiveFurureTest {
	@Test public void wrap() {
		// Any CompletableFuture can be wrapped.
		CompletableFuture<String> cf = new CompletableFuture<>();
		ReactiveFuture<String> rf = ReactiveFuture.wrap(cf);
		assertSame(cf, rf.completable());
		// Wrapping the same CompletableFuture always returns the same ReactiveFuture.
		assertSame(rf, ReactiveFuture.wrap(cf));
	}
	@Test public void create() {
		// ReactiveFuture can also construct its own CompletableFuture if none is provided explicitly.
		ReactiveFuture<String> rf = new ReactiveFuture<>();
		assertNotNull(rf.completable());
		// Wrapping the CompletableFuture just returns the ReactiveFuture that created it.
		assertSame(rf, ReactiveFuture.wrap(rf.completable()));
	}
}
