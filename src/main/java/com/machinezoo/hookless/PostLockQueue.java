// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.function.*;

class PostLockQueue {
	private final Object lock;
	PostLockQueue(Object lock) {
		this.lock = lock;
	}
	private List<Runnable> queue = new ArrayList<>();
	void run(Runnable section) {
		synchronized (lock) {
			section.run();
		}
		List<Runnable> tasks = queue;
		queue = null;
		for (Runnable task : tasks)
			task.run();
	}
	<T> T eval(Supplier<T> section) {
		T result;
		synchronized (lock) {
			result = section.get();
		}
		List<Runnable> tasks = queue;
		queue = null;
		for (Runnable task : tasks)
			task.run();
		return result;
	}
	void post(Runnable task) {
		queue.add(task);
	}
}
