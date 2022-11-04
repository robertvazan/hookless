// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import static java.util.stream.Collectors.*;
import java.util.*;
import java.util.function.*;
import com.machinezoo.closeablescope.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.stagean.*;
import it.unimi.dsi.fastutil.objects.*;

/*
 * This is the thread-local thing that is essential for hookless way of doing reactivity.
 * Any computation running within the scope will log dependencies in it, making seamless reactivity possible.
 * 
 * It is tempting to generalize reactive scope into universal thread-local context and use it to solve a variety of problems,
 * for example widget dependencies (CSS/JS in the document head) in pushmode or propagating deadlines.
 * This would however introduce a lot of complexity without really solving any of those complex problems.
 * It is better to have multiple libraries, each with its own context object and let apps combine them.
 * In case of hookless, specialized context class allows us to manipulate the scope while it is not active
 * and to inspect it in detail and modify it intelligently. General context would make all of that very difficult.
 * 
 * Reactive scope is deliberately not thread-safe. People nearly always want to run reactive computations single-threaded.
 * Supporting the rare case of parallelized computations would cost us a lot of locking overhead and some complexity.
 * Parallelized computations should instead create new scope (each with its own freezes and pins) for every thread.
 * Dependencies from these scopes can then be merged into single resulting scope.
 * The only downside of this solution is that parallelized computations have limited access to freeze and pin functionality.
 */
/**
 * Thread-local context for reactive computations that collects reactive dependencies.
 */
@StubDocs
public class ReactiveScope {
	public ReactiveScope() {
		OwnerTrace.of(this).alias("scope");
	}
	/*
	 * Scope must be the active scope for dependency logging to occur.
	 * There is at most one active scope per thread, pointed to by a thread-local variable.
	 * 
	 * It is possible for thread to have no active scope. We don't bother to provide default scope,
	 * because CurrentReactiveScope already provides much better fallback mechanism.
	 * ReactiveScope therefore shouldn't be used directly, because hard-coded references
	 * to ReactiveScope.current() would result in null pointer exceptions,
	 * which is particularly likely and problematic in unit tests.
	 */
	private static final ThreadLocal<ReactiveScope> current = new ThreadLocal<ReactiveScope>();
	public static ReactiveScope current() {
		return current.get();
	}
	/*
	 * Scopes happily nest on a single stack.
	 * This is done by making each scope remember its parent and restore the parent upon completion.
	 */
	private ReactiveScope parent;
	/*
	 * Scopes are designed to be used with Java's try-with-resources construct.
	 * We could also offer run(Runnable) and supply(Supplier) methods, but those are more suitable for APIs that are used frequently.
	 * ReactiveScope is a fairly low-level API, so we will just provide the try-with-resources variant.
	 * 
	 * We do not create tracing scope for every reactive scope/computation even though it feels natural.
	 * Reactive computations generally shouldn't have side effects,
	 * which means nothing interesting happens while they run and there is nothing interesting to log in the trace.
	 * Computations might take a long time, which is interesting, but they usually consume nearly all the time
	 * of their containing thread pool task, which is already traced via reactive trigger.
	 */
	public CloseableScope enter() {
		if (parent != null)
			throw new IllegalStateException("Cannot enter the same reactive scope recursively.");
		parent = current.get();
		current.set(this);
		return () -> {
			current.set(parent);
			parent = null;
		};
	}
	/*
	 * It is often useful to prevent dependency logging within some scope.
	 * This could be done by temporarily activating a throwaway scope,
	 * but it is more efficient and arguably cleaner to set active scope to null.
	 */
	public static CloseableScope ignore() {
		ReactiveScope parent = current.get();
		current.set(null);
		return () -> current.set(parent);
	}
	/*
	 * We can only depend on one version of every variable, which means we need a map from variables to their versions.
	 */
	private Object2LongMap<ReactiveVariable<?>> dependencies = new Object2LongOpenHashMap<>();
	/*
	 * However, we cannot expose a data structure like this through the API, because it can easily change.
	 * We will instead let callers iterate over a sequence of version objects.
	 * Efficiency is not that important. We can provide ReactiveTrigger with direct access if needed.
	 * We most importantly care about clean API here.
	 */
	public Collection<ReactiveVariable.Version> versions() {
		/*
		 * If there are invalidated pins from previous blocking computations,
		 * we have to assume that our version list is incomplete, because pin dependencies have not been preserved.
		 * We will return single out-of-date version in order to force reevaluation of the reactive computation,
		 * which will hopefully complete without blocking and there will be therefore no more invalidated pins.
		 * 
		 * Invalidated pins do not invalidate blocking computations, because the next blocking computation will have the same pins.
		 * If we invalidated blocking computations too, it would result in busy looping since the pins wouldn't get updated by more computations.
		 * Blocking computations only need to wait for completion of their blocking reads in order to make progress.
		 */
		if (!blocked && pins != null && !pins.valid()) {
			return Collections.singletonList(new ReactiveVariable.Version(invalidated, invalidated.version() - 1));
		}
		/*
		 * This is quite inefficient, but the API allows for very high efficiency. We could have a custom collection.
		 */
		List<ReactiveVariable.Version> versions = dependencies.object2LongEntrySet().stream()
			.map(e -> new ReactiveVariable.Version(e.getKey(), e.getLongValue()))
			.collect(toList());
		return Collections.unmodifiableCollection(versions);
	}
	private static ReactiveVariable<Object> invalidated = new ReactiveVariable<>();
	static {
		invalidated.set(new Object());
	}
	/*
	 * ReactiveVariable will call this method to add itself to the list of dependencies.
	 * If the same variable is read twice, we want to remember version from the first access.
	 * That's why we first check the dependency map for duplicates.
	 * 
	 * ReactiveVariable could have just as well called the method with explicit version,
	 * but that one is a tiny bit smaller, because it cannot assume the supplied version is the latest one.
	 */
	public void watch(ReactiveVariable<?> variable) {
		Objects.requireNonNull(variable);
		if (!dependencies.containsKey(variable))
			dependencies.put(variable, variable.version());
	}
	/*
	 * It is also possible to add specific version to the dependency list.
	 * This is useful when manipulating the scope explicitly, for example when merging dependencies from inner scope.
	 * Since version numbers are increasing, we can easily determine
	 * whether the inserted or the already stored version is the earlier one.
	 * 
	 * We might theoretically need other methods to manipulate dependencies, but ReactiveScope is conceptually a builder,
	 * so we are satisfied with an API that lets callers rebuild the scope with some dependencies modified or filtered out.
	 */
	public void watch(ReactiveVariable<?> variable, long version) {
		Objects.requireNonNull(variable);
		long previous = dependencies.getLong(variable);
		if (previous == 0 || version < previous)
			dependencies.put(variable, version);
	}
	/*
	 * Blocking is necessary to prevent jerky display of incomplete results followed by complete results a split-second later.
	 * It also prevents propagation of incomplete results through machine interfaces like the initial HTTP GET in PushMode.
	 * Any reactive data source can block the computation if it cannot return complete data immediately.
	 * Caller is then expected to re-run the computation later (when current one is invalidated).
	 */
	private boolean blocked;
	public boolean blocked() {
		return blocked;
	}
	/*
	 * We don't provide corresponding unblock() method, because blocking can be temporarily disabled using nonblocking()
	 * and the builder-like nature of ReactiveScope means that callers can rebuild the scope when they want the blocking flag cleared.
	 */
	public void block() {
		blocked = true;
		/*
		 * If we block, then there will be another computation.
		 * That other computation wouldn't be able to track dependencies for pins created in this one.
		 * So let's mark all the pins as invalidated, so that following computations know about this.
		 */
		pins().invalidate();
	}
	/*
	 * Instead of having special non-blocking flag on the scope (as it used to be in past versions),
	 * nonblocking() uses nesting and inspection of scopes to achieve the same in a very general way.
	 * It creates nested scope and runs the non-blocking operations within the nested scope.
	 * When done, it copies dependencies to the current scope and importantly avoids copying the blocked flag.
	 */
	public static CloseableScope nonblocking() {
		/*
		 * It is quite possible the parent scope is null, especially during test runs.
		 * We should avoid creating nested scope in that case,
		 * because the non-blocking context is expected to be transparent in every way except for blocking.
		 */
		ReactiveScope parent = current();
		if (parent == null) {
			return () -> {};
		}
		ReactiveScope scope = OwnerTrace.of(new ReactiveScope())
			.parent(parent)
			.alias("nonblocking")
			.generateId()
			.target();
		/*
		 * Share freezes and pins with parent scope. Callers expect non-blocking context to be transparent for freezes and pins.
		 * For pins, don't propagate changes automatically to the parent scope, because we will want to filter them later.
		 */
		scope.freezes(parent.freezes());
		scope.pins().parent(parent.pins());
		/*
		 * Inherit blocking from parent if that one is already blocked.
		 * This will prevent confusion inside the non-blocking scope, including incorrect pinning of blocking results.
		 * Non-blocking scope only prevents blocking from propagating from the inner scope to the outer one.
		 */
		if (parent.blocked())
			scope.block();
		CloseableScope computation = scope.enter();
		return () -> {
			computation.close();
			/*
			 * Propagate pins to parent scope. If there are any, then it means the parent scope is not blocked.
			 * Any pins collected in the nested scope must have been collected before the nested scope was marked as blocking.
			 * So these are effectively standard pins computed without reliance on any blocking operations.
			 * 
			 * While pins are propagated, pin invalidation is not.
			 * If pins were invalidated in nested scope, it must have happened because of blocking in the nested scope.
			 * Since this is non-blocking scope, we don't want to propagate any effects of blocking, including pin invalidation.
			 */
			for (Object key : scope.pins().keys())
				parent.pins().set(key, scope.pins().get(key));
			/*
			 * We must be careful here. Nested scope's versions() could return single out-of-date version due to invalidated pins.
			 * Nevertheless, if pins have been invalidated, then nested scope must have been blocked.
			 * If it was blocked, then checking of pin invalidation is disabled and versions() behaves normally.
			 * All that means we can safely query versions() of the nested scope here and assume standard behavior.
			 */
			for (ReactiveVariable.Version version : scope.versions())
				parent.watch(version.variable(), version.number());
		};
	}
	/*
	 * Since other threads can change global state at any time, we would normally have to safeguard against it.
	 * 
	 * Code with race rules:
	 * 
	 * if (query() > 0)
	 * process(query())
	 *
	 * Safeguarding against race rules:
	 * 
	 * int value = query();
	 * if (value > 0)
	 * process(value);
	 *
	 * Freezing offers another solution. It evaluates some code only once per scope's lifetime,
	 * making sure there is only one stable result. If query() internally freezes its result,
	 * we don't have to safeguard against race rules.
	 * 
	 * Freezing implementation:
	 * 
	 * int query() {
	 * return CurrentReactiveScope.freeze("query", () -> someDatabaseRead());
	 * }
	 * 
	 * We can then rely on the value to not change during one computation:
	 * 
	 * if (query() > 0)
	 * process(query())
	 * 
	 * Freezing is therefore a non-essential convenience, but it is nevertheless very useful.
	 */
	private ReactiveFreezes freezes;
	public <T> T freeze(Object key, Supplier<T> supplier) {
		return freezes().freeze(key, supplier);
	}
	public ReactiveFreezes freezes() {
		/*
		 * Lazily created to avoid the overhead for computations that don't need freezes.
		 */
		if (freezes == null)
			freezes = new ReactiveFreezes();
		return freezes;
	}
	public void freezes(ReactiveFreezes freezes) {
		/*
		 * The supplied freeze container may be null. In that case, we create new container the first time it is needed.
		 */
		this.freezes = freezes;
	}
	/*
	 * Blocking may never stop if the reactive computation changes dependencies every time,
	 * perhaps because some continuously changing dependency is used as a pointer to other blocking dependencies.
	 * We therefore allow the reactive computation to "pin" some values.
	 * All caches and other scope-manipulating code is expected to share pins over a sequence of blocking computations.
	 * Pins are a separate class to aid in this sharing and to also hide internal representation.
	 * 
	 * Pins are a fundamental construct. Synchronous code can remember data it acquired before executing blocking operations.
	 * Reactive code similarly needs a way to remember its own decisions and data acquired before blocking,
	 * which, in the case of reactive code, happened in previous reactive computation that was marked as blocked.
	 * 
	 * Code below is equivalent to corresponding freeze code above.
	 */
	private ReactivePins pins;
	public <T> T pin(Object key, Supplier<T> supplier) {
		/*
		 * Forward all pinning requests through freeze(), so that freezes are a superset of pins,
		 * at least of those pins accessed during current computation.
		 * 
		 * This has the side-effect that blocking computation in the supplier, after being rejected as a pin,
		 * will be accepted as a freeze. This way blocking computations are automatically downgraded from pins to freezes.
		 */
		return freeze(key, () -> pins().pin(key, supplier));
	}
	public ReactivePins pins() {
		if (pins == null)
			pins = new ReactivePins();
		return pins;
	}
	public void pins(ReactivePins pins) {
		this.pins = pins;
	}
	@Override
	public String toString() {
		return OwnerTrace.of(this).toString();
	}
}
