// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class ReactiveBatchCache<T> {
	private final Supplier<T> factory;
	private final ReactiveVariable<T> vars = OwnerTrace
		.of(new ReactiveVariable<>(new ReactiveValue<T>(null, null, true))
			.keepalive(this))
		.parent(this)
		.target();
	private boolean started;
	private final ReactiveThread thread = OwnerTrace
		.of(new ReactiveThread(this::refresh))
		.parent(this)
		.target();
	public ReactiveBatchCache(Supplier<T> factory) {
		Objects.requireNonNull(factory);
		this.factory = factory;
		OwnerTrace.of(this).alias("bcache");
	}
	public ExecutorService executor() {
		return thread.executor();
	}
	public ReactiveBatchCache<T> executor(ExecutorService executor) {
		thread.executor(executor);
		return this;
	}
	public boolean weak() {
		return thread.daemon();
	}
	public ReactiveBatchCache<T> weak(boolean weak) {
		thread.daemon(weak);
		return this;
	}
	public ReactiveBatchCache<T> draft(T value) {
		return initialize(value, true);
	}
	public ReactiveBatchCache<T> initial(T value) {
		return initialize(value, false);
	}
	public synchronized ReactiveBatchCache<T> start() {
		started = true;
		thread.start();
		return this;
	}
	public ReactiveBatchCache<T> stop() {
		thread.stop();
		return this;
	}
	public synchronized T get() {
		ReactiveValue<T> latest = vars.value();
		if (!started)
			start();
		return latest.get();
	}
	private void refresh() {
		ReactiveValue<T> value = ReactiveValue.capture(factory);
		if (value.blocking())
			return;
		vars.value(value);
	}
	private synchronized ReactiveBatchCache<T> initialize(T value, boolean draft) {
		if (started)
			throw new IllegalStateException();
		vars.value(new ReactiveValue<>(value, vars.value().blocking() && draft));
		return this;
	}
	@Override public synchronized String toString() {
		return OwnerTrace.of(this) + " = " + vars.value();
	}
}
