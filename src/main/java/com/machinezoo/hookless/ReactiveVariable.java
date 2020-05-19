// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import com.machinezoo.hookless.util.*;
import io.opentracing.*;
import io.opentracing.util.*;

/*
 * Reactive variable can either hold value directly or it can be used as a bridge
 * by event-driven code to communicate changes to the hookless world.
 * That is accomplished by creating ReactiveVariable<Object> and calling set(new Object()).
 * 
 * Reactive variable is thus a universal reactive primitive rather than just one kind of reactive data source.
 * We are referencing it directly in reactive scope and reactive trigger instead of indirect references via abstract base class.
 * All other reactive data sources internally use reactive variable either to directly store state or to trigger invalidations.
 */
public class ReactiveVariable<T> {
	/*
	 * There is no perfect solution for equality testing, so we resort to configuration.
	 * 
	 * Case for full equality (via Object.equals):
	 * A lot of code does trivial writes like overwriting boolean flag with the same value.
	 * This would cause lots of unnecessary invalidations, making it hard for reactive computations to settle.
	 * Full equality testing prevents unnecessary invalidations.
	 * It is also likely to be the less surprising and the more useful option, which is why we make it the default.
	 * 
	 * Case for reference equality (via == operator):
	 * Full equality testing can be computationally expensive. It can cause surprising performance problems.
	 * Reference equality might be also expected for something that is called "variable".
	 * Since there are sufficiently many situations where it could be useful, we expose it as a configurable option.
	 * 
	 * Other options (not offered):
	 * - no equality testing: unnecessarily wasteful, reference equality already cheap enough
	 */
	private volatile boolean equality = true;
	public boolean equality() {
		return equality;
	}
	public ReactiveVariable<T> equality(boolean equality) {
		this.equality = equality;
		return this;
	}
	/*
	 * Every reactive variable has an associated version number, which is incremented after every change.
	 * We start with version 1, so that we can use 0 as a special value elsewhere, especially in reactive scope's dependency map.
	 * 
	 * The alternative solution, one used in past versions of hookless, is to have a version object.
	 * New version object would be created after every change. The upside is that version objects are easier to handle.
	 * The main downside of version objects is that they are not sorted, making it impossible to tell which version is earlier.
	 * Version numbers enable correct merging of multiple dependency sets, for example from parallel computations.
	 * Version numbers also look better in the debugger and other tools and they show how many times the variable changed.
	 * Version numbers might also improve performance of dependency tracking a tiny bit.
	 */
	private volatile long version = 1;
	public long version() {
		return version;
	}
	/*
	 * The object representation of version is nevertheless still needed in various APIs.
	 * We will provide an inner class that represents either latest or any specified version.
	 */
	public class Version {
		public ReactiveVariable<?> variable() {
			return ReactiveVariable.this;
		}
		private final long number;
		public long number() {
			return number;
		}
		public Version() {
			number = ReactiveVariable.this.version;
		}
		public Version(long version) {
			number = version;
		}
		/*
		 * Make version objects directly comparable.
		 */
		@Override public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null || !(obj instanceof ReactiveVariable.Version))
				return false;
			@SuppressWarnings("unchecked") Version other = (Version)obj;
			return variable() == other.variable() && number == other.number;
		}
		@Override public int hashCode() {
			return Objects.hash(variable(), number);
		}
		@Override public String toString() {
			return "Version " + number + " of " + ReactiveVariable.this;
		}
	}
	/*
	 * Triggers can subscribe to receive change notifications from the variable.
	 * We do not offer general subscription API, because it adds complexity.
	 * People can always just create new trigger and receive notifications through it.
	 * 
	 * When trigger is fired, it is automatically unsubscribed.
	 * This is expected by trigger, which cannot be rearmed once triggered.
	 * But even if we allowed anyone to subscribe, we wouldn't want to send change notifications repeatedly,
	 * because dependent objects might be slow to react and we don't want to overwhelm them when changes happen rapidly.
	 * We instead wait for dependent objects to resubscribe (via new trigger) and only notify them of subsequent changes.
	 * 
	 * Since triggers subscribe long after variable read, the variable might have changed meantime.
	 * That's why triggers must double-check version number after they subscribe to detect such changes.
	 * 
	 * Triggers are smart enough not to subscribe themselves twice. We don't need the set for subscriptions.
	 * But triggers can unsubscribe in random order, which makes the set necessary for fast unsubscription.
	 * 
	 * The set is weak, so that variables don't block garbage collection of triggers.
	 * In the hookless world, strong references only go in the direction from reactive consumers to reactive sources.
	 * Weak references are always used in the opposite direction.
	 * That means the trigger must be held alive by something. Subscription alone wouldn't protect it from GC.
	 * Reactive consumers must take care to keep reference to their trigger.
	 * That makes triggers hard to use, but event-driven programming is always hard.
	 * 
	 * Assisticant and maybe other reactive frameworks offer an API that produces notifications
	 * when the list of listeners (triggers in hookless) becomes empty or non-empty.
	 * While this looks like a convenient way to deallocate native resources tied to a cache when no one uses it,
	 * it is inherently unsafe, because the trigger list can become empty through garbage collection of triggers
	 * rather than explicit removal and the empty list situation might not be detected.
	 * While we could use Java API's features to monitor garbage collection of weak references to compensate for this,
	 * we would still deliver empty trigger list notification long after the triggers stopped being used,
	 * because garbage collector can take a long time to get around to collecting unused (and thus unreferenced) triggers.
	 * Such late notification is nearly useless, so it's better to not offer the API at all
	 * and save ourselves some complexity and performance issues.
	 */
	private Set<ReactiveTrigger> triggers = newTriggerSet();
	private static Set<ReactiveTrigger> newTriggerSet() {
		return Collections.newSetFromMap(new WeakHashMap<ReactiveTrigger, Boolean>());
	}
	synchronized void subscribe(ReactiveTrigger result) {
		Objects.requireNonNull(result);
		triggers.add(result);
	}
	synchronized void unsubscribe(ReactiveTrigger result) {
		triggers.remove(result);
	}
	/*
	 * Storing reactive value in the reactive variable has the advantage
	 * that complex reactive sources like caches can store exceptions and blocking flag in the variable.
	 * Reading the cache (or other source) can then be implemented as a read from reactive variable.
	 * This simplifies implementation of reactive sources.
	 * 
	 * The value field is volatile in order to allow reads without the overhead of locking.
	 * 
	 * The value field is never null.
	 */
	private volatile ReactiveValue<T> value;
	public ReactiveValue<T> value() {
		/*
		 * This is what makes the variable reactive. We let reactive scope track the variable as a dependency.
		 * 
		 * The variable must be added to the scope before value field is read in order to avoid race rules,
		 * because we are running this method unsynchronized for performance reasons (relying on volatile flag)
		 * and there could be a concurrent write before the variable is tracked (and version read) and reading the value.
		 * If that happens, the tracked version will be old and trigger will detect it when it is armed.
		 */
		ReactiveScope current = ReactiveScope.current();
		if (current != null)
			current.watch(this);
		/*
		 * Unsynchronized field read relies on volatile flag on the field.
		 */
		return value;
	}
	public void value(ReactiveValue<T> value) {
		Objects.requireNonNull(value);
		/*
		 * Since full equality checking can be slow, we will perform it outside of any synchronized section to avoid blocking.
		 * 
		 * While this makes the write behavior more complicated in case of concurrent writes,
		 * the behavior is still correct in the sense that one write wins.
		 * If all the concurrent writes compare unequal, one of them will win the subsequent modification of the variable.
		 * if all writes compare equal, no change will occur and that is correct behavior for all of the writes.
		 * If some writes compare equal and some unequal, then one of the unequal writes is allowed to win.
		 * 
		 * Reads of the fields 'equality' and 'value' don't need to be synchronized, because the fields are volatile,
		 * but we have to make a copy of 'value' field, because we are going to access it several times.
		 */
		ReactiveValue<T> previous = this.value;
		if (!(equality ? previous.equals(value) : previous.same(value))) {
			Set<ReactiveTrigger> notified = null;
			synchronized (this) {
				/*
				 * It is important to avoid assigning new value when equality test is positive.
				 * Value change must happen only if there is a corresponding version change.
				 * Otherwise consecutive reads from the variable could return different objects for the same version.
				 * This would cause numerous such objects to be cached in dependent caches for a long time, wasting memory.
				 * 
				 * The worst case scenario is a 100MB value that is subsequently cached by thousands of dependent caches.
				 * If every one of those caches reads different (but equal) instance of the value, a terabyte of RAM could be wasted.
				 * Changing the value only when version changes ensures that all these caches hold reference to the same value.
				 */
				this.value = value;
				++version;
				if (!triggers.isEmpty()) {
					/*
					 * Completely replacing the set lets us fire triggers below without synchronization.
					 * It also resets set capacity to zero, so oversized trigger sets don't linger around.
					 */
					notified = triggers;
					triggers = newTriggerSet();
				}
			}
			/*
			 * This is where reactivity happens. We will notify reactive triggers about the change in this variable.
			 * 
			 * We are firing triggers immediately even though this write might be a part of a larger batch of changes.
			 * Older versions of hookless had the concept of "transactions" that would fire all triggers at the very end.
			 * This was intended to prevent invalidating the same cache twice with the same high-level write.
			 * It turns out this is not very useful. It doesn't improve throughput much, but it spreads complexity everywhere.
			 * We are now firing triggers without delay and rely on executor's FIFO processing to limit double invalidations.
			 * 
			 * Fire triggers outside of the synchronized block. Triggers might run their callbacks inline,
			 * which might take a lot of time and these callbacks may perform writes back to the variable.
			 */
			if (notified != null) {
				/*
				 * We don't want to trace every variable write, because tracing is expensive.
				 * We only enable it here when we are sure that at least trigger will fire.
				 * This is not a problem, because the tracing is intended primarily for callback graph anyway.
				 */
				Span span = GlobalTracer.get().buildSpan("hookless.change")
					.withTag("component", "hookless")
					.start();
				OwnerTrace.of(this).fill(span);
				try (Scope trace = GlobalTracer.get().activateSpan(span)) {
					for (ReactiveTrigger trigger : notified) {
						/*
						 * Normally, we would wrap callbacks in Exceptions.log(), but calling reactive trigger is safe.
						 * It is our code and we know it wouldn't throw exceptions.
						 */
						trigger.fire();
					}
				}
			}
		}
	}
	/*
	 * We are registering the variable in reactive scope before every read.
	 * The variable is recorded only once, but reactive scope has to check every time that the variable is already tracked.
	 * This check is implemented as a hash lookup. Providing fast hashCode() implementation speeds it up considerably.
	 */
	private final int hashCode = ThreadLocalRandom.current().nextInt();
	@Override public int hashCode() {
		return hashCode;
	}
	/*
	 * Methods set() and get() are packing/unpacking variants of corresponding value() methods above.
	 * These are the more commonly used ones and they thus get the nicer names.
	 * 
	 * Method get() unpacks the reactive value into current reactive scope.
	 * This is what's normally expected when accessing reactive variable.
	 * 
	 * Method set() wraps supplied object as reactive value.
	 * We are ignoring blocking flag in current reactive computation here, which means the packing is not complete.
	 * This is because setting blocking flag inside of a reactive variable might be surprising and result in subtle bugs.
	 * Most uses of set() intend to set non-blocking value. When blocking is intended, callers can use value(...) method instead.
	 */
	public T get() {
		return value().get();
	}
	public void set(T result) {
		value(new ReactiveValue<>(result));
	}
	/*
	 * We provide some convenience constructors. Besides convenience, they are also faster than writing the variable after construction.
	 */
	public ReactiveVariable(ReactiveValue<T> value) {
		Objects.requireNonNull(value);
		this.value = value;
		OwnerTrace.of(this).alias("var");
	}
	public ReactiveVariable(T result) {
		this(new ReactiveValue<>(result));
	}
	public ReactiveVariable() {
		this(new ReactiveValue<>());
	}
	/*
	 * When reactive variable is embedded in another reactive object,
	 * oftentimes it is the only thing that has strong references pointing to it.
	 * This causes trouble when the reactive variable is supposed to be updated by something
	 * that has only weak references pointing to it and it gets garbage collected.
	 * The variable is then never updated and reactivity is lost.
	 * 
	 * This is particularly true of any higher-level object holding reactive trigger.
	 * When the higher-level object is collected, the trigger is too, and it never fires.
	 * 
	 * In order to prevent premature collection, such high-level objects should call keepalive()
	 * on the reactive variable passing themselves as the parameter.
	 * This should be done even if the high-level object doesn't use weakrefs itself,
	 * because weakrefs could be introduced at yet higher level.
	 */
	@SuppressWarnings("unused") private Object keepalive;
	public ReactiveVariable<T> keepalive(Object keepalive) {
		this.keepalive = keepalive;
		return this;
	}
	@Override public String toString() {
		return OwnerTrace.of(this) + " = " + value;
	}
}
