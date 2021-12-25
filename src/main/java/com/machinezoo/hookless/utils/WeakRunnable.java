// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.utils;

import java.lang.ref.*;
import java.util.*;
import java.util.function.*;
import com.machinezoo.stagean.*;

/*
 * Hookless relies on garbage collection of weakly referenced objects, so that apps don't have to explicitly unsubscribe from sources.
 * Extra garbage is not just a burden on memory. Reactive zombies might keep responding to changes and eat up processor time too.
 * 
 * Unfortunately, some reactive objects that are otherwise weakly reachable intermittently acquire strong reachability.
 * This happens chiefly in two cases: running methods and queued thread pool tasks, both triggered by callbacks from changing dependencies.
 * Running methods are very hard to make weak, but we don't worry about that, because their number is limited by thread count.
 * Queued tasks can however keep around thousands or millions of reactive zombies if they are allowed to hold strong references.
 * 
 * This class makes it easy to create weak Runnable from instance method references that can be safely scheduled on executors.
 * It should be used whenever reactive objects need to schedule their execution in a thread pool.
 */
/**
 * Weak reference to an instance method.
 * 
 * @param <T>
 *            type of object that defines the method
 */
@StubDocs
@NoTests
public class WeakRunnable<T> implements Runnable {
	private final WeakReference<T> weakref;
	private final Consumer<T> method;
	/*
	 * We cannot automatically break up existing Runnable created from bound instance method reference.
	 * We have to ask calling code to separate instance reference and method reference explicitly.
	 */
	public WeakRunnable(T target, Consumer<T> method) {
		Objects.requireNonNull(target);
		Objects.requireNonNull(method);
		weakref = new WeakReference<>(target);
		this.method = method;
	}
	@Override
	public void run() {
		T target = weakref.get();
		if (target != null)
			method.accept(target);
	}
}
