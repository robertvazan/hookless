// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.stagean.*;
import io.opentracing.*;
import io.opentracing.util.*;

/**
 * Reactive data source holding single {@link ReactiveValue}.
 * Changes can be observed by using {@link ReactiveTrigger} directly or
 * by implementing reactive computation using one of the higher level APIs, for example {@link ReactiveThread}.
 * <p>
 * {@link ReactiveVariable} is also used as a bridge between event-driven code and hookless-based code.
 * Event-driven code can instantiate {@code ReactiveVariable<Object>} and call {@code set(new Object())}
 * on it whenever it wants to wake up dependent reactive computations.
 * <p>
 * {@link ReactiveVariable} is thus a universal reactive primitive rather than just one kind of reactive data source.
 * It is referenced directly in {@link ReactiveScope} and {@link ReactiveTrigger}.
 * All other reactive data sources internally use {@code ReactiveVariable} either to directly store state or to trigger invalidations.
 * <p>
 * {@link ReactiveVariable} is thread-safe. All methods are safe to call concurrently from multiple threads.
 * 
 * @param <T>
 *            type of the stored value
 * 
 * @see ReactiveTrigger
 * @see ReactiveCollections
 */
@DraftDocs("link to docs for reactive data sources, reactive computation")
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
	 * - no equality testing: unnecessarily wasteful, reference equality is already cheap enough
	 */
	private volatile boolean equality = true;
	/**
	 * Returns {@code true} if this {@link ReactiveVariable} performs full equality check on assignment.
	 * This method merely returns what was last passed to {@link #equality(boolean)}. Defaults to {@code true}.
	 * 
	 * @return {@code true} if full equality check is enabled, {@code false} if reference equality is used
	 * 
	 * @see #equality(boolean)
	 * @see ReactiveValue#equals(Object)
	 * @see ReactiveValue#same(ReactiveValue)
	 */
	public boolean equality() {
		return equality;
	}
	/**
	 * Configures full or reference equality.
	 * When this {@link ReactiveVariable} is assigned, dependent reactive computations are notified about the change if there is any.
	 * In order to check whether the stored value has changed, {@link ReactiveVariable} can either perform
	 * full equality check via {@link ReactiveValue#equals(Object)}
	 * or simple reference equality check via {@link ReactiveValue#same(ReactiveValue)}.
	 * This method can be used to configure which equality test is used. Default is to do full equality check.
	 * <p>
	 * This method should be called before the {@link ReactiveVariable} is used for the first time.
	 * 
	 * @param equality
	 *            {@code true} to do full equality check, {@code false} to test only reference equality
	 * @return {@code this} (fluent method)
	 * 
	 * @see #equality()
	 * @see ReactiveValue#equals(Object)
	 * @see ReactiveValue#same(ReactiveValue)
	 */
	public ReactiveVariable<T> equality(boolean equality) {
		this.equality = equality;
		return this;
	}
	/*
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
	/**
	 * Returns current version of this {@link ReactiveVariable}.
	 * Every {@link ReactiveVariable} has an associated version number, which is incremented after every change. Initial version is 1.
	 * Version number does not change if there was no actual change as determined by {@link #equality()} setting.
	 * Reading the version number with this method does not create reactive dependency on the {@link ReactiveVariable}.
	 * 
	 * @return current version of this {@link ReactiveVariable}
	 * 
	 * @see Version
	 */
	public long version() {
		return version;
	}
	/*
	 * The object representation of version is nevertheless still needed in various APIs.
	 * We will provide an inner class that represents either latest or any specified version.
	 */
	/**
	 * Reference to particular version of {@link ReactiveVariable}.
	 * Version of the {@link ReactiveVariable} is already exposed via {@link ReactiveVariable#version()}.
	 * This is just a convenience wrapper.
	 * It represents particular version of particular {@link ReactiveVariable}.
	 * 
	 * @see ReactiveVariable#version()
	 */
	public static class Version {
		private final ReactiveVariable<?> variable;
		/**
		 * Returns the {@link ReactiveVariable} this is a version of.
		 * This is the {@link ReactiveVariable} that was passed to the constructor.
		 * 
		 * @return {@link ReactiveVariable} this object is a version of
		 */
		public ReactiveVariable<?> variable() {
			return variable;
		}
		private final long number;
		/**
		 * Returns the version number of the version this object represents.
		 * This is the version number that was passed to the constructor
		 * or determined at construction time by calling {@link ReactiveVariable#version()}.
		 * 
		 * @return version number of the version this object represents
		 * 
		 * @see ReactiveVariable#version()
		 */
		public long number() {
			return number;
		}
		/**
		 * Creates new {@link Version} object representing specified version of the {@link ReactiveVariable}.
		 * This constructor is useful in rare cases,
		 * for example when multiple versions have to be merged by taking minimum or maximum.
		 * Constructor {@link Version#Version(ReactiveVariable)}
		 * should be used when just capturing current version.
		 * <p>
		 * Return values of {@link ReactiveVariable#version()} are always positive.
		 * The {@code version} parameter of this constructor additionally allows special zero value.
		 * 
		 * @param variable
		 *            {@link ReactiveVariable} that will have its version tracked by this object
		 * @param version
		 *            non-negative version number tracked by this object
		 * @throws NullPointerException
		 *             if {@code variable} is {@code null}
		 * @throws IllegalArgumentException
		 *             if {@code version} is negative
		 */
		public Version(ReactiveVariable<?> variable, long version) {
			Objects.requireNonNull(variable);
			if (version < 0)
				throw new IllegalArgumentException();
			this.variable = variable;
			number = version;
		}
		/**
		 * Creates new {@link Version} object representing current version of {@link ReactiveVariable}.
		 * Current version is determined by calling {@link ReactiveVariable#version()}.
		 * Constructor {@link Version#Version(ReactiveVariable, long)}
		 * can be used to specify different version number.
		 * 
		 * @param variable
		 *            {@link ReactiveVariable} that will have its version tracked by this object
		 * @throws NullPointerException
		 *             if {@code variable} is {@code null}
		 */
		public Version(ReactiveVariable<?> variable) {
			this(variable, variable.version);
		}
		/*
		 * Make version objects directly comparable.
		 */
		/**
		 * Compares this version with another {@link Version}.
		 * Two versions are equal if they the same {@link #variable()} and {@link #number()}.
		 * 
		 * @param obj
		 *            object to compare this version to
		 * @return {@code true} if {@code obj} represents the same version, {@code false} otherwise
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null || !(obj instanceof Version))
				return false;
			Version other = (Version)obj;
			return variable() == other.variable() && number == other.number;
		}
		/**
		 * Calculates hash code of this version.
		 * Hash code incorporates {@link #variable()} identity and version {@link #number}.
		 * 
		 * @return hash code of this {@link Version}
		 */
		@Override
		public int hashCode() {
			return Objects.hash(variable(), number);
		}
		/**
		 * Returns diagnostic string representation of this version.
		 * {@link ReactiveVariable} and its {@link ReactiveValue} is included in the result,
		 * but no reactive dependency is created by calling this method.
		 * 
		 * @return string representation of this version
		 */
		@Override
		public String toString() {
			return "Version " + number + " of " + variable;
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
	/**
	 * Reads the current {@link ReactiveValue} from this {@link ReactiveVariable} and sets up reactive dependency.
	 * <p>
	 * This {@link ReactiveVariable} is recorded as a dependency in current {@link ReactiveScope}
	 * (as identified by {@link ReactiveScope#current()}.
	 * If there is no current {@link ReactiveScope}, no dependency is recorded
	 * and this method just returns the {@link ReactiveValue}.
	 * <p>
	 * {@link ReactiveValue}'s {@link ReactiveValue#get()} is not called,
	 * so <a href="https://hookless.machinezoo.com/blocking">reactive blocking</a> and exception, if any, are not propagated.
	 * They are left encapsulated in the returned {@link ReactiveValue}.
	 * Call {@link #get()} if propagation of reactive blocking and exceptions is desirable.
	 * 
	 * @return current {@link ReactiveValue} of this {@link ReactiveVariable}, never {@code null}
	 * 
	 * @see #get()
	 * @see #value(ReactiveValue)
	 */
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
	/**
	 * Sets current {@link ReactiveValue} of this {@link ReactiveVariable} and notifies dependent reactive computations.
	 * This is the more general version of {@link #set(Object)} that allows setting arbitrary {@link ReactiveValue}.
	 * <p>
	 * The {@code value} may have {@link ReactiveValue#blocking()} flag set and it may contain {@link ReactiveValue#exception()}.
	 * This is useful in situations when {@link ReactiveVariable} is used as a bridge between hookless-based code and event-driven code.
	 * The event-driven code may signal that it is not yet ready by setting its {@link ReactiveVariable}
	 * to a <a href="https://hookless.machinezoo.com/blocking">blocking</a> value.
	 * It may also communicate exceptions reactively by wrapping them in {@link ReactiveValue} and storing it in {@link ReactiveVariable}.
	 * <p>
	 * If current {@link ReactiveValue} is actually changed as determined by {@link #equality()} setting,
	 * {@link #version()} is incremented and dependent reactive computations
	 * (the ones that accessed {@link #value()} or {@link #get()}) are notified about the change.
	 * If there is no actual change (new value compares equal to the old value per {@link #equality()} setting),
	 * then the state of this {@link ReactiveVariable} does not change, old {@link ReactiveValue} is kept,
	 * {@link #version()} remains the same, and no change notifications are sent.
	 * 
	 * @param value
	 *            new {@link ReactiveValue} to store in this {@link ReactiveVariable}
	 * @throws NullPointerException
	 *             if {@code value} is {@code null}
	 * 
	 * @see #set(Object)
	 * @see #value()
	 */
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
				 * Value change must happen only if there is corresponding version change.
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
	/**
	 * Returns identity hash code.
	 * This implementation is semantically identical to {@link Object#hashCode()}.
	 * It is just a little faster in order to speed up operations that use {@link ReactiveVariable} as a key in {@link Map}.
	 * 
	 * @return identity hash code
	 */
	@Override
	public int hashCode() {
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
	/**
	 * Returns the value held by this {@link ReactiveVariable}.
	 * In most situations, this is the more convenient alternative to {@link #value()}.
	 * It is equivalent to calling {@link #value()} and then calling {@link ReactiveValue#get()} on the returned {@link ReactiveValue}.
	 * <p>
	 * If the stored {@link ReactiveValue} has {@link ReactiveValue#blocking()} flag set,
	 * <a href="https://hookless.machinezoo.com/blocking">reactive blocking</a> is propagated into current {@link ReactiveScope} if there is any.
	 * If the stored {@link ReactiveValue} holds an exception, the exception is propagated wrapped in {@link CompletionException}.
	 * Otherwise this method just returns {@link ReactiveValue#result()}.
	 * <p>
	 * This {@link ReactiveVariable} is recorded as a dependency in current {@link ReactiveScope}
	 * (as identified by {@link ReactiveScope#current()} if there is any.
	 * 
	 * @throws CompletionException
	 *             if the stored {@link ReactiveValue} holds an exception
	 * 
	 * @return current value stored in this {@link ReactiveVariable}
	 * 
	 * @see #value()
	 * @see ReactiveValue#get()
	 * @see #set(Object)
	 */
	public T get() {
		return value().get();
	}
	/**
	 * Sets current value of this {@link ReactiveVariable}.
	 * In most situations, this is the more convenient alternative to {@link #value(ReactiveValue)}.
	 * It is equivalent to wrapping {@code value} in {@link ReactiveValue} and passing it to {@link #value(ReactiveValue)}.
	 * <p>
	 * If current {@link ReactiveValue} is actually changed as determined by {@link #equality()} setting,
	 * {@link #version()} is incremented and dependent reactive computations
	 * (the ones that accessed {@link #value()} or {@link #get()}) are notified about the change.
	 * Note that change is detected if the old {@link ReactiveValue} has
	 * {@link ReactiveValue#blocking()} flag set or if it holds an exception ({@link ReactiveValue#exception()}).
	 * If there is no actual change (new {@link ReactiveValue} compares equal to the old {@link ReactiveValue} per {@link #equality()} setting),
	 * then the state of this {@link ReactiveVariable} does not change, old {@link ReactiveValue} is kept,
	 * {@link #version()} remains the same, and no change notifications are sent.
	 * 
	 * @param value
	 *            new value to store in this {@link ReactiveVariable}
	 * 
	 * @see #value(ReactiveValue)
	 * @see ReactiveValue#ReactiveValue(Object)
	 * @see #get()
	 */
	public void set(T value) {
		value(new ReactiveValue<>(value));
	}
	/*
	 * We provide some convenience constructors. Besides convenience, they are also faster than writing the variable after construction.
	 */
	/**
	 * Creates new instance holding specified {@link ReactiveValue}.
	 * 
	 * @param value
	 *            initial value of the {@link ReactiveVariable}
	 * @throws NullPointerException
	 *             if {@code value} is {@code null}
	 */
	public ReactiveVariable(ReactiveValue<T> value) {
		Objects.requireNonNull(value);
		this.value = value;
		OwnerTrace.of(this).alias("var");
	}
	/**
	 * Creates new instance holding specified {@code value}.
	 * The {@link ReactiveValue} in the new {@link ReactiveVariable} will have {@code false} {@link ReactiveValue#blocking()} flag.
	 * 
	 * @param value
	 *            initial value of the {@link ReactiveVariable}
	 */
	public ReactiveVariable(T value) {
		this(new ReactiveValue<>(value));
	}
	/**
	 * Creates new instance with {@code null} value.
	 * The new {@link ReactiveVariable} will contain {@link ReactiveValue} with {@code null} {@link ReactiveValue#result()}.
	 */
	public ReactiveVariable() {
		this(new ReactiveValue<>());
	}
	@SuppressWarnings("unused")
	private Object keepalive;
	/**
	 * Adds strong reference to the specified target object.
	 * This is sometimes useful to control garbage collection.
	 * Only one target object is supported. If this method is called twice, the first target is discarded.
	 * <p>
	 * Hookless keeps strong references in the direction from reactive computations to their reactive dependencies
	 * and weak references in opposite direction. This usually results in expected garbage collector behavior.
	 * <p>
	 * However, when {@link ReactiveVariable} is embedded in a higher level reactive object,
	 * reactive computations hold strong references to the embedded {@link ReactiveVariable}
	 * instead of pointing to the outer reactive object, which makes the outer object vulnerable to premature collection.
	 * If the outer object is supposed to exist as long as its embedded {@link ReactiveVariable} is referenced,
	 * for example when it has {@link ReactiveTrigger} subscribed to changes in the {@link ReactiveVariable},
	 * the outer object should call this method on its embedded {@link ReactiveVariable}, passing itself as the target object.
	 * This will ensure the outer object will live for as long as its embedded {@link ReactiveVariable}.
	 * 
	 * @param keepalive
	 *            target object that will be strongly referenced by this {@link ReactiveVariable}
	 * @return {@code this} (fluent method)
	 */
	public ReactiveVariable<T> keepalive(Object keepalive) {
		this.keepalive = keepalive;
		return this;
	}
	/**
	 * Returns diagnostic string representation of this {@link ReactiveVariable}.
	 * Stored {@link ReactiveValue} is included in the result,
	 * but no reactive dependency is created by calling this method.
	 * 
	 * @return string representation of this {@link ReactiveVariable}
	 */
	@Override
	public String toString() {
		return OwnerTrace.of(this) + " = " + value;
	}
}
