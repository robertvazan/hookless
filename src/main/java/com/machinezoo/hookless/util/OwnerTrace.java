// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.util;

import static java.util.stream.Collectors.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.google.common.cache.*;
import com.machinezoo.stagean.*;
import io.opentracing.*;
import it.unimi.dsi.fastutil.objects.*;

/*
 * Opentracing doesn't work that well for reactive code, because reactive code inverts call stacks.
 * If child object decides that the event doesn't have to be propagated to the parent,
 * then information about the parent (in the form of tags) is omitted from the trace.
 * Such headless traces without parent information are often meaningless.
 * 
 * This is a non-standard extension to opentracing that models ownership (parent-child) relationships
 * and allows child objects to include tags of all their ancestors in child spans.
 * This makes traces understandable even if child object doesn't propagate the event to its parent.
 * 
 * Some objects are short-lived and we should probably model them as spans rather than span owners.
 * This is particularly true for sequences of blocking reactive computations.
 * However, if we model such blocking computation sequences as spans,
 * these overarching spans will be composed of smaller spans representing individual computations
 * and these smaller spans will then have two parents: the overarching span (from above) and the span
 * that woke up the reactive object and triggered the new computation (from below).
 * It is not clear how well does opentracing and its implementations support multiple parents,
 * especially when the wakeup span belongs to a different trace.
 * This is why we currently don't try to represent short-lived objects as spans.
 * It is nevertheless possible to add this feature in the future.
 * 
 * But even with current implementation, seemingly independent events often end up in one trace.
 * This is because blocking operations (like database reads) are expected to
 * link the span that caused blocking to the span that represents end of the blocking operation.
 * This only breaks down when two or more reactive computations trigger the same blocking operation.
 * In that case only the first operation will have complete trace recorded.
 *
 * Tags and ownership information added here is also used to construct informative toString() methods.
 *
 * We will use volatile and final fields extensively to avoid expensive locking.
 * There are also many other optimizations, to keep the cost of owner tracing to minimum.
 */
/**
 * Trace of object ancestors for easier debugging and tracing.
 */
@NoTests
@StubDocs
@DraftApi("should be in a separate library")
public class OwnerTrace<T> {
	/*
	 * We don't want to force every class to carry OwnerTrace as a member.
	 * Many classes cannot even expose any member, for example when they are exposed only through some interface.
	 * We will instead associate tracing information to any object via a map with weak keys.
	 * 
	 * WeakHashMap would be the simplest solution, but it uses hashCode/equals,
	 * which results in surprising behavior when owner trace information is added to collections.
	 * We will instead use Guava's CacheBuilder that automatically uses System.identityHashCode()
	 * and reference equality when weakKeys() is specified. It is also automatically synchronized.
	 * The downside is that Guava cache is likely quite inefficient for the simple use case we need.
	 * 
	 * Use of weak map has the unpleasant consequence that we cannot hold reference back to the target object,
	 * because the map holds hard reference to its values even if the key is no longer referenced by anything.
	 * If we kept a hard reference to the target object, which is used as a key in the map,
	 * we would prevent collection of our own map entry and create a memory leak.
	 * Fortunately, reference to the target object is only needed in our builder API.
	 * So we reserve OwnerTrace itself as our builder API and use private object as a value in the weak map.
	 * This will cost us an extra object allocation every time we access OwnerTrace.
	 */
	private static LoadingCache<Object, OwnerTraceData> all = CacheBuilder.newBuilder()
		.weakKeys()
		.build(CacheLoader.from(OwnerTraceData::new));
	public static <T> OwnerTrace<T> of(T target) {
		return new OwnerTrace<T>(target, all.getUnchecked(target));
	}
	private final T target;
	public T target() {
		return target;
	}
	private final OwnerTraceData data;
	private OwnerTrace(T target, OwnerTraceData data) {
		Objects.requireNonNull(target);
		this.target = target;
		this.data = data;
	}
	private static class OwnerTraceData {
		/*
		 * All fields are actually stored here since the outer class is a short-lived builder.
		 * We will use volatile fields to avoid expensive locking when the data is accessed.
		 */
		volatile String alias;
		volatile OwnerTag tags;
		volatile OwnerTraceData parent;
		/*
		 * We could store classname (the default for alias) in separate field,
		 * but it's much simpler to write it directly into the alias field as the default value.
		 */
		OwnerTraceData(Object target) {
			/*
			 * Static objects are likely to have class as their parent, but we don't want just "Class" as an alias.
			 * Class name is taken instead as an alias in that case. Since class name is used as default alias for instances too,
			 * classes used in ownership chain as both instances and static classes will have to explicitly specify aliases.
			 */
			if (target instanceof Class)
				alias = ((Class<?>)target).getSimpleName();
			else
				alias = target.getClass().getSimpleName();
		}
	}
	/*
	 * Aliasing allows using neat short namespaces for ancestor tags instead of the lengthy class name.
	 */
	public OwnerTrace<T> alias(String alias) {
		data.alias = alias;
		return this;
	}
	/*
	 * All tags are explicitly set. On-demand computed tags are rarely useful and they are dangerous and inefficient.
	 * 
	 * Linked list of (mostly) immutable tag structures seems to be the fastest solution for short tag lists.
	 * Its downside is that it complicates updating tag value, but then we mostly care about read speed.
	 */
	private static class OwnerTag {
		final String key;
		/*
		 * This will require type-testing and casting when tagging the tracing span.
		 * Maybe there is a way to optimize that casting by having multiple typed fields here,
		 * but I cannot know without profiling.
		 */
		volatile Object value;
		final OwnerTag next;
		OwnerTag(String key, Object value, OwnerTag next) {
			this.key = key;
			this.value = value;
			this.next = next;
		}
	}
	public OwnerTrace<T> tag(String key, Object value) {
		Objects.requireNonNull(key);
		/*
		 * Silently ignore null tag values. This simplifies code that would otherwise have to perform null check.
		 */
		if (value != null) {
			OwnerTag head = data.tags;
			for (OwnerTag tag = head; tag != null; tag = tag.next) {
				if (tag.key.equals(key)) {
					tag.value = value;
					return this;
				}
			}
			data.tags = new OwnerTag(key, value, head);
		}
		return this;
	}
	/*
	 * Sometimes we cannot provide any good tags that would identify the object,
	 * but we still want to have a way to tell one instance from the other.
	 * We provide convenience ID generator here to allow for that.
	 * 64-bit counter will never overflow.
	 */
	private static final AtomicLong counter = new AtomicLong();
	public OwnerTrace<T> generateId() {
		return tag("id", counter.incrementAndGet());
	}
	/*
	 * This is the key. We establish ownership hierarchy to make child scopes meaningful
	 * even if there's no parent scope in the trace.
	 */
	public OwnerTrace<T> parent(Object parent) {
		if (parent instanceof OwnerTrace)
			data.parent = ((OwnerTrace<?>)parent).data;
		else if (parent == null)
			data.parent = null;
		else
			data.parent = OwnerTrace.of(parent).data;
		return this;
	}
	/*
	 * When using OwnerTrace in tracing spans and toString(), we have to namespace tags of different ancestors.
	 * By default, tag's namespace is identical to object's alias, but it can be numbered in case there are alias conflicts.
	 */
	private static class Namespace {
		OwnerTraceData data;
		/*
		 * May differ from from alias if there are name conflicts among ancestors.
		 */
		String name;
	}
	/*
	 * The linked list of ancestors we actually store only allows backward iteration of ancestor chain.
	 * We have to materialize the ancestor chain and reverse it in order to iterate it in forward direction.
	 * The materialized ancestor list is also necessary to create uniquely named namespaces.
	 * 
	 * All this is relatively computationally expensive and unfortunately it has to run often.
	 * Unless we can find a way to execute it less often (e.g. by having a way to detect whether tracing is active),
	 * we will probably have to optimize this code in the future.
	 */
	private List<Namespace> namespaces() {
		List<Namespace> namespaces = new ArrayList<>();
		for (OwnerTraceData ancestor = data; ancestor != null; ancestor = ancestor.parent) {
			Namespace ns = new Namespace();
			ns.data = ancestor;
			namespaces.add(ns);
		}
		Collections.reverse(namespaces);
		Object2IntMap<String> numbering = new Object2IntOpenHashMap<>(namespaces.size());
		for (Namespace ns : namespaces) {
			/*
			 * Make a copy since the alias could be changed in another thread (unlikely but possible).
			 */
			String alias = ns.data.alias;
			if (!numbering.containsKey(alias)) {
				ns.name = alias;
				numbering.put(alias, 2);
			} else {
				int number = numbering.getInt(alias);
				ns.name = alias + number;
				numbering.put(alias, number + 1);
			}
		}
		return namespaces;
	}
	/*
	 * We can now use the constructed ownership hierarchy and tags to create informative tracing span.
	 * 
	 * This is quite expensive operation. Ideally, we would like to skip it if the trace is going to be sampled out.
	 * There is unfortunately no way to detect disabled tracing, perhaps because sampling is done when trace is complete.
	 * We will have to be careful where we inject tracing. The operation burdened with tracing better be infrequent enough.
	 * In the future, we might wish to be able to disable tracing in all or part of hookless code for performance reasons.
	 */
	public Span fill(Span span) {
		Objects.requireNonNull(span);
		List<Namespace> namespaces = namespaces();
		span.setTag("owner", namespaces.stream().map(ns -> ns.name).collect(joining(".")));
		for (Namespace ns : namespaces) {
			for (OwnerTag tag = ns.data.tags; tag != null; tag = tag.next) {
				String key = ns.name + "." + tag.key;
				Object value = tag.value;
				/*
				 * Opentracing API only takes certain types of variables, so cast appropriately.
				 * We will convert arbitrary objects via toString(). This is a bit dangerous,
				 * but callers are expected to be careful what are they setting as the tag value.
				 */
				if (value instanceof String)
					span.setTag(key, (String)value);
				else if (value instanceof Number)
					span.setTag(key, (Number)value);
				else if (value instanceof Boolean)
					span.setTag(key, (boolean)value);
				else
					span.setTag(key, value.toString());
			}
		}
		return span;
	}
	/*
	 * This is used to implement toString() on the tagged object.
	 * The logic is the same as in fill() method.
	 */
	@Override
	public String toString() {
		/*
		 * Just materializing the complete tag map is the simplest implementation.
		 * This is not performance-critical code, so we aim for code simplicity rather than performance.
		 * We are constructing a TreeMap in order to force display in sorted order.
		 */
		Map<String, Object> sorted = new TreeMap<>();
		List<Namespace> namespaces = namespaces();
		for (Namespace ns : namespaces)
			for (OwnerTag tag = ns.data.tags; tag != null; tag = tag.next)
				sorted.put(ns.name + "." + tag.key, tag.value);
		return namespaces.stream().map(ns -> ns.name).collect(joining(".")) + sorted;
	}
}
