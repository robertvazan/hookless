// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.slf4j.*;
import com.machinezoo.stagean.*;
import io.opentracing.*;
import io.opentracing.util.*;

/*
 * Reactive trigger is a low-level reactive primitive that is usually hidden inside higher-level reactive constructs.
 * It is the only way to get change notifications from reactive variables, but it is easy to set it up
 * to monitor single variable. Custom callbacks for variable changes thus aren't lost.
 * It is also used to create callbacks for all high-level reactive constructs, which can be done
 * by reading their state inside reactive scope and arming a trigger with variables collected by the scope.
 * This is why no other reactive construct offers any callbacks.
 * 
 * Trigger has many advantages over plain callbacks:
 * - monitoring multiple variables
 * - protection from race rules between version read and subscription to changes
 * - protection from accidental garbage collection due to weak references in variables
 * - choice between callback and state polling (armed(), fired(), closed())
 * - explicit forced firing even if no variable changes
 * 
 * The standard sequence of calls is: arm(), fire(), close().
 * Some calls can be skipped and the trigger will still behave reasonably.
 * Methods fire() and close() may be called repeatedly.
 * 
 * Trigger implements AutoCloseable, so that it can be used in try-with-resources,
 * although that's usually only useful in unit tests.
 */
/**
 * Callback for changes in {@link ReactiveVariable}s.
 */
@StubDocs
public class ReactiveTrigger implements AutoCloseable {
	public ReactiveTrigger() {
		OwnerTrace.of(this).alias("trigger");
	}
	/*
	 * We will keep a list of reactive variables we have subscribed to, so that we can unsubscribe from them.
	 * Null variable list in combination with 'armed' flag also indicates that subscription is still in progress.
	 * 
	 * Variable list is implemented as a plain array to save a little memory since triggers stay around for a long time.
	 */
	private ReactiveVariable<?>[] variables;
	/*
	 * Arming is separate from constructor, so that callback and tags can be set first.
	 * The version list usually comes straight from reactive scope,
	 * but we don't reference scope anywhere, so that reactive trigger can be also used without it.
	 * 
	 * Arming detects outdated versions and it might call fire() immediately.
	 * Callers must ensure they are prepared to receive callback when they call arm(),
	 * which usually means that arm() is the last step they are taking before waiting for the callback.
	 */
	private boolean armed;
	public synchronized boolean armed() {
		return armed;
	}
	public void arm(Collection<ReactiveVariable.Version> versions) {
		Objects.requireNonNull(versions);
		synchronized (this) {
			/*
			 * Contrary to fire() and close() calls, we put some constraints on when arm() can be called,
			 * because misplaced arm() call is a sign of a bug.
			 */
			if (armed)
				throw new IllegalStateException("Cannot arm the trigger twice.");
			if (closed)
				throw new IllegalStateException("Trigger was already closed.");
			armed = true;
		}
		/*
		 * Subscription runs unsynchronized, because it could take some time and we might need to fire() during it.
		 */
		List<ReactiveVariable<?>> subscribed = new ArrayList<>();
		for (ReactiveVariable.Version version : versions) {
			version.variable().subscribe(this);
			subscribed.add(version.variable());
			/*
			 * If the variable has already changed, fire immediately.
			 * This check must be done only after subscription to avoid race rules.
			 */
			if (version.number() != version.variable().version()) {
				fire();
				break;
			}
		}
		ReactiveVariable<?>[] compact = subscribed.toArray(new ReactiveVariable<?>[subscribed.size()]);
		ReactiveVariable<?>[] unsubscribed = null;
		synchronized (this) {
			/*
			 * We have to immediately unsubscribe all variables if the trigger was closed meantime.
			 * If we just fired without closing, we keep the variables until close() is called.
			 */
			if (closed)
				unsubscribed = compact;
			else
				variables = compact;
		}
		/*
		 * Unsubscription runs unsynchronized, because it could take some time.
		 */
		if (unsubscribed != null)
			unsubscribe(unsubscribed);
	}
	/*
	 * Reactive variables keep a set of all subscribed triggers.
	 * Lookups in this set are sped up a bit by precomputing hashCode().
	 */
	private final int hashCode = ThreadLocalRandom.current().nextInt();
	@Override
	public int hashCode() {
		return hashCode;
	}
	/*
	 * Callers can configure callback to be run when fire() is called.
	 * If left null, firing can be still detected by polling fired() method.
	 * 
	 * The callback has to run close() as otherwise the trigger would stay subscribed to variables until GC deletes it.
	 * Not calling close() from callback also risks premature GC of the trigger, causing the callback to never run.
	 */
	private Runnable callback;
	public synchronized Runnable callback() {
		return callback;
	}
	public synchronized ReactiveTrigger callback(Runnable callback) {
		this.callback = callback;
		return this;
	}
	private static final Logger logger = LoggerFactory.getLogger(ReactiveTrigger.class);
	/*
	 * Method fire() is usually called by reactive variables when they change,
	 * but we also allow manual firing and fire() may be also called early when arm() detects outdated version.
	 */
	private boolean fired;
	public synchronized boolean fired() {
		return fired;
	}
	public void fire() {
		Runnable callback = null;
		synchronized (this) {
			/*
			 * Do not invoke the callback if the trigger was already closed.
			 * Closing the trigger indicates that the owner is no longer interested in the callback.
			 * We however avoid throwing an exception, because fire() could be called by changed variables
			 * that are unaware of trigger closing and even manual calls to fire() may come from other threads
			 * that don't necessarily know that some other thread has already closed the trigger.
			 */
			if (!fired && !closed) {
				fired = true;
				callback = this.callback;
			}
		}
		/*
		 * Callback must be invoked outside of the synchronized block, because it could take a long time.
		 */
		if (callback != null) {
			/*
			 * Create new tracing span whenever invoking a callback.
			 * Reactive trigger usually inherits tags from all ancestors,
			 * so this trace span will describe what was invalidated by recent variable change.
			 */
			Span span = GlobalTracer.get().buildSpan("hookless.fire")
				.withTag("component", "hookless")
				.start();
			OwnerTrace.of(this).fill(span);
			try (Scope trace = GlobalTracer.get().activateSpan(span)) {
				/*
				 * Don't let exceptions escape from callbacks. Callbacks are supposed to handle their own exceptions.
				 * If they fail to do so, we will just catch and log the exception.
				 */
				ExceptionLogging.log(logger).run(callback);
			}
		}
	}
	/*
	 * We require users of reactive trigger to explicitly close() it.
	 * Theoretically, that's not necessary, because the calling code can just set the callback and forget the trigger.
	 * The problem is that with no reference to the trigger and only weak backreferences from variables,
	 * the trigger can be garbage-collected early, which will automatically remove it from reactive variables,
	 * and the callback will then never run. This can lead to surprising unreproducible bugs that are hard to fix.
	 * 
	 * If callers are required to close() the trigger after use, then they have to hold a reference to it.
	 * Furthermore, since close() synchronizes on the trigger, the trigger is required to exist until close() is called.
	 * Without synchronization, GC would be free to collect it if trigger's fields are not used or they are cached in registers/stack.
	 * We could also use Java 9's Reference.reachabilityFence​(), but locking appears to be sufficient.
	 * 
	 * Reactive trigger implements Closeable in order to make close() easier to use in tests and for other method-local use cases.
	 */
	private boolean closed;
	public synchronized boolean closed() {
		return closed;
	}
	@Override
	public void close() {
		ReactiveVariable<?>[] unsubscribed = null;
		synchronized (this) {
			/*
			 * Tolerate multiple close() calls. This can happen when cleanup is done "just in case".
			 */
			if (!closed) {
				closed = true;
				/*
				 * If the trigger wasn't closed yet and variable list is null,
				 * it means that either arm() is in progress or that it was never called.
				 * If it was never called, then merely setting the 'closed' flag will prevent future arm() calls.
				 * If it is in progress, then setting the 'closed' flag will inform it that it has to unsubscribe when finished.
				 */
				if (variables != null) {
					unsubscribed = variables;
					variables = null;
				}
			}
		}
		/*
		 * Unsubscription runs unsynchronized, because it could take some time.
		 */
		if (unsubscribed != null)
			unsubscribe(unsubscribed);
	}
	private void unsubscribe(ReactiveVariable<?>[] unsubscribed) {
		for (ReactiveVariable<?> variable : unsubscribed)
			variable.unsubscribe(this);
	}
	@Override
	public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
