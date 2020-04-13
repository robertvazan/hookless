// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.utils;

import java.util.*;
import java.util.concurrent.atomic.*;
import com.google.common.cache.*;
import io.opentracing.*;

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
		else
			data.parent = OwnerTrace.of(parent).data;
		return this;
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
		fill(data, span, false);
		/*
		 * If there are tag conflicts among ancestors, we can either prefer higher or lower ancestors.
		 * We prefer higher ancestors, because they likely contain more recognizable context information.
		 * This also makes it simpler to implement ancestor iteration.
		 */
		for (OwnerTraceData ancestor = data; ancestor != null; ancestor = ancestor.parent)
			fill(ancestor, span, true);
		return span;
	}
	private static void fill(OwnerTraceData data, Span span, boolean prefixed) {
		/*
		 * Avoid race rules by reading the tags field only once.
		 */
		OwnerTag head = data.tags;
		if (head != null) {
			/*
			 * Prefixes will conflict in case we have two ancestors of the same type, for example nested reactive scopes.
			 * We could number such conflicting ancestors, but since such conflicts are rare and inconsequential,
			 * we will be lazy, do the simplest (overwriting) implementation, and wait for the first bug report complaining about it.
			 */
			String prefix = prefixed ? data.alias + "." : null;
			for (OwnerTag tag = head; tag != null; tag = tag.next) {
				String key = prefix != null ? prefix + tag.key : tag.key;
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
		} else {
			/*
			 * If there are no tags, then it wouldn't be apparent from the trace that this object is present.
			 * We will therefore at least include its classname or alias if nothing else.
			 * 
			 * We could also force generation of ID tag at this point, but that's unnecessary,
			 * because higher-level objects are expected to contain sufficient identifying information.
			 * We just need to make ownership hierarchy apparent from the tags
			 * and simple boolean will suffice for that purpose.
			 */
			span.setTag(data.alias, true);
		}
	}
	/*
	 * This is used to implement toString() on the tagged object.
	 * The logic is the same as in fill() method.
	 */
	@Override public String toString() {
		/*
		 * Just materializing the complete tag map is the simplest implementation.
		 * This is not performance-critical code, so we aim for code simplicity rather than performance.
		 * We are constructing a TreeMap in order to force display in sorted order.
		 */
		Map<String, Object> sorted = new TreeMap<>();
		/*
		 * If there are no tags, we don't add fallback any tag for the object itself like we do with ancestors,
		 * because the fact that the target object is present is obvious from its classname
		 * that is always included in toString() output.
		 */
		for (OwnerTag tag = data.tags; tag != null; tag = tag.next)
			sorted.put(tag.key, tag.value);
		for (OwnerTraceData ancestor = data.parent; ancestor != null; ancestor = ancestor.parent) {
			/*
			 * Avoid race rules by reading the tags field only once.
			 */
			OwnerTag head = ancestor.tags;
			if (head != null) {
				String prefix = ancestor.alias + ".";
				for (OwnerTag tag = head; tag != null; tag = tag.next)
					sorted.put(prefix + tag.key, tag.value);
			} else
				sorted.put(ancestor.alias, true);
		}
		return target.getClass().getSimpleName() + sorted;
	}
}
