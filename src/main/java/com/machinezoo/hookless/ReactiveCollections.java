// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless;

import java.util.*;

/*
 * We don't want to expose an ocean of new classes wrapping every kind of collection.
 * We instead define wrapping methods, one for every type of collection, and let them take various options.
 * This makes for a simple, concise API.
 * 
 * There is no perfect way to define reactive collections. We try to offer good defaults
 * and we allow configuration to cover cases that the defaults cannot.
 * There will always be cases that cannot be covered and apps sometimes have to do explicit invalidation.
 * 
 * We are not placing invalidation into finally block, because it is assumed
 * that throwing write operations made no change to the collection.
 * This might not be entirely true, but it should be true of any correctly implemented collection.
 * 
 * Reactivity is not particularly compatible with read-write operations that collections expose,
 * for example when set's add() returns true if the set was modified.
 * Read-write operations create new dependency and subsequently invalidate that same dependency.
 * That causes infinite re-running of the reactive computation that triggers such operation.
 * We try to fight this phenomenon in several ways:
 * 
 * 1. Invalidate only if progress is made.
 * If exception is thrown, we can usually skip invalidation. If exception is not thrown,
 * the return value can be often used to detect whether the collection was changed.
 * If it was not changed, we can skip invalidation.
 * Disallowing null values makes this check possible in more cases.
 * Optionally, reactive collections can check for full equality instead of reference equality
 * to better detect cases where no real progress is being made and invalidation can be skipped.
 * 
 * 2. Avoid leaking collection state via return value and exceptions.
 * Write methods often return status that makes them read-write even though callers usually ignore the status.
 * Callers can disable (silence) the return value or instruct reactive collection to ignore it
 * to avoid collecting unnecessary dependency. The same can be done for state-based exceptions.
 * This makes most methods either read-only or write-only, avoiding the troubling read-write case.
 * 
 * 3. Expect callers to indicate their intent.
 * If callers wrap collection calls in ReactiveScope.ignore(), no dependency will be collected.
 * Callers can do this to indicate that they are only interested in write part of the operation, not read.
 * 
 * It seems attractive to add reactiveObject(T) and reactiveObjectTree(T) methods
 * that would turn any POJO object or object tree into reactive data structure.
 * That however involves a lot of complexity related to dynamically generating wrapper classes.
 * We could use standard JRE Proxy class, restricting the feature to interfaces,
 * but Proxy-based interceptors are unacceptably slow for an in-memory data structure.
 * Given that this feature is rarely needed, it is better to not provide it for now
 * and instead expect callers to find another way, for example using immutable objects.
 */
public class ReactiveCollections {
	/*
	 * There are many ways to supply options to wrapping methods, but NIO-style ellipsis option parameters are neat.
	 * We could use an option object, but that would make the API more verbose when none or just one option is used.
	 * We could also have a builder where options are specified, but this is again verbose for typical cases.
	 * Having ellipsis option parameters has the advantage that different wrapper methods can have different defaults.
	 */
	public static interface Option {
	}
	private static class Options {
		boolean compareValues;
		boolean ignoreWriteStatus;
		boolean ignoreWriteExceptions;
		boolean silenceWriteStatus;
		boolean silenceWriteExceptions;
	}
	private static interface ExecutableOption extends Option {
		void apply(Options options);
	}
	/*
	 * Checking for full equality during writes may reduce the number of invalidations.
	 * False by default since standard Java collections don't do any equality check during item writes.
	 */
	public static final Option COMPARE_VALUES = new ExecutableOption() {
		@Override public void apply(Options options) {
			options.compareValues = true;
		}
	};
	/*
	 * Writes may signal collection state via return value or specific exceptions.
	 * By default we observe the collection as a dependency to account for it.
	 * We let callers disable this feature in case they know they wouldn't check status/exceptions
	 * and they wish to avoid collecting unnecessary dependencies.
	 * This is equivalent to wrapping writes with ReactiveScope.ignore().
	 * This has no effect on methods that are inherently read-write.
	 * False by default to honor read-write semantics of standard Java collections.
	 */
	public static final Option IGNORE_WRITE_STATUS = new ExecutableOption() {
		@Override public void apply(Options options) {
			options.ignoreWriteStatus = true;
		}
	};
	public static final Option IGNORE_WRITE_EXCEPTIONS = new ExecutableOption() {
		@Override public void apply(Options options) {
			options.ignoreWriteExceptions = true;
		}
	};
	/*
	 * If write status or exceptions are not observed as dependencies,
	 * but they are still used by callers, it can result in surprisingly non-reactive code.
	 * To prevent that, we can silence return status and state-related exceptions from write methods.
	 * Callers are then forced to be explicit about whether they want read or write operation.
	 * This has no effect on methods that are inherently read-write.
	 * False by default to honor interface semantics of Java collections.
	 */
	public static final Option SILENCE_WRITE_STATUS = new ExecutableOption() {
		@Override public void apply(Options options) {
			options.silenceWriteStatus = true;
		}
	};
	public static final Option SILENCE_WRITE_EXCEPTIONS = new ExecutableOption() {
		@Override public void apply(Options options) {
			options.silenceWriteExceptions = true;
		}
	};
	private static Options options(Option[] flags) {
		Options options = new Options();
		for (Option flag : flags)
			((ExecutableOption)flag).apply(options);
		return options;
	}
	/*
	 * Base class for both collections and iterators.
	 * Its main job is to provide utility methods to access reactive variable.
	 */
	private static class ReactiveCollectionObject {
		final ReactiveVariable<Object> version;
		/*
		 * Copying all the flags here probably isn't an ideal solution, but it will do for now.
		 */
		final Options options;
		ReactiveCollectionObject(Options options) {
			version = OwnerTrace.of(new ReactiveVariable<Object>())
				.parent(this)
				.target();
			this.options = options;
		}
		ReactiveCollectionObject(ReactiveCollectionObject master) {
			version = master.version;
			options = master.options;
		}
		void observe() {
			version.get();
		}
		void observeStatus() {
			if (!options.ignoreWriteStatus)
				observe();
		}
		void observeException() {
			if (!options.ignoreWriteExceptions)
				observe();
		}
		void observeStatusAndException() {
			if (!options.ignoreWriteStatus || !options.ignoreWriteExceptions)
				observe();
		}
		void invalidate() {
			version.set(new Object());
		}
		void invalidateIf(boolean changed) {
			if (changed)
				invalidate();
		}
		void invalidateIfChanged(Object previous, Object next) {
			if (!options.compareValues && previous != next || options.compareValues && !Objects.equals(previous, next))
				invalidate();
		}
		RuntimeException silenceException(RuntimeException ex) {
			if (options.silenceWriteExceptions)
				return new SilencedCollectionException(ex);
			return ex;
		}
		boolean silenceStatus(boolean changed) {
			if (options.silenceWriteStatus)
				return true;
			return changed;
		}
		<T> T silenceResult(T result) {
			if (options.silenceWriteStatus)
				return null;
			return result;
		}
	}
	private static class SilencedCollectionException extends RuntimeException {
		private static final long serialVersionUID = -2919538247896857962L;
		SilencedCollectionException(RuntimeException silenced) {
			super(silenced);
		}
	}
	private static class ReactiveIterator<T> extends ReactiveCollectionObject implements Iterator<T> {
		final Iterator<T> inner;
		ReactiveIterator(ReactiveCollectionObject master, Iterator<T> inner) {
			super(master);
			this.inner = inner;
		}
		@Override public boolean hasNext() {
			observe();
			return inner.hasNext();
		}
		@Override public T next() {
			observe();
			return inner.next();
		}
	}
	public static <T> Collection<T> collection(Collection<T> collection, Option... options) {
		Objects.requireNonNull(collection);
		return new ReactiveCollection<>(collection, options(options));
	}
	private static class ReactiveCollection<T> extends ReactiveCollectionObject implements Collection<T> {
		final Collection<T> inner;
		ReactiveCollection(Collection<T> inner, Options options) {
			super(options);
			OwnerTrace.of(this).alias("collection");
			this.inner = inner;
		}
		ReactiveCollection(Collection<T> inner, ReactiveCollectionObject master) {
			super(master);
			OwnerTrace.of(this).alias("collection");
			this.inner = inner;
		}
		@Override public boolean add(T item) {
			Objects.requireNonNull(item);
			observeStatusAndException();
			boolean changed;
			try {
				changed = inner.add(item);
			} catch (IllegalStateException ex) {
				throw silenceException(ex);
			}
			invalidateIf(changed);
			return silenceStatus(changed);
		}
		@Override public boolean addAll(Collection<? extends T> collection) {
			for (T item : collection)
				Objects.requireNonNull(item);
			observeStatusAndException();
			boolean changed;
			try {
				changed = inner.addAll(collection);
			} catch (IllegalStateException ex) {
				/*
				 * We don't know whether any elements were added, so invalidate just in case.
				 */
				invalidate();
				throw silenceException(ex);
			}
			invalidateIf(changed);
			return silenceStatus(changed);
		}
		@Override public void clear() {
			inner.clear();
			invalidate();
		}
		@Override public boolean contains(Object item) {
			observe();
			return inner.contains(item);
		}
		@Override public boolean containsAll(Collection<?> collection) {
			observe();
			return inner.containsAll(collection);
		}
		@Override public boolean equals(Object obj) {
			observe();
			return inner.equals(obj);
		}
		@Override public int hashCode() {
			observe();
			return inner.hashCode();
		}
		@Override public boolean isEmpty() {
			observe();
			return inner.isEmpty();
		}
		@Override public Iterator<T> iterator() {
			return new ReactiveIterator<>(this, inner.iterator());
		}
		@Override public boolean remove(Object item) {
			observeStatus();
			boolean changed = inner.remove(item);
			invalidateIf(changed);
			return silenceStatus(changed);
		}
		@Override public boolean removeAll(Collection<?> collection) {
			observeStatus();
			boolean changed = inner.removeAll(collection);
			invalidateIf(changed);
			return silenceStatus(changed);
		}
		@Override public boolean retainAll(Collection<?> collection) {
			observeStatus();
			boolean changed = inner.retainAll(collection);
			invalidateIf(changed);
			return silenceStatus(changed);
		}
		@Override public int size() {
			observe();
			return inner.size();
		}
		@Override public Object[] toArray() {
			observe();
			return inner.toArray();
		}
		@Override public <U extends Object> U[] toArray(U[] array) {
			observe();
			return inner.toArray(array);
		}
		@Override public String toString() {
			return OwnerTrace.of(this) + ": " + inner;
		}
	}
	private static class ReactiveListIterator<T> extends ReactiveIterator<T> implements ListIterator<T> {
		final ListIterator<T> inner;
		ReactiveListIterator(ReactiveCollectionObject master, ListIterator<T> inner) {
			super(master, inner);
			this.inner = inner;
		}
		@Override public void add(T e) {
			Objects.requireNonNull(e);
			inner.add(e);
			invalidate();
		}
		@Override public boolean hasPrevious() {
			/*
			 * Result of this method is predictable for ArrayList, thus no need to observe dependency,
			 * but LinkedList can be modified during the lifetime of the iterator
			 * and callers can then use this method to probe the collection for such changes.
			 * Since we don't know which List implementation are we working with,
			 * we better observe the dependency to cover all cases.
			 */
			observe();
			return inner.hasPrevious();
		}
		@Override public int nextIndex() {
			/*
			 * LinkedList may have changing and thus unpredictable element offsets due to background modifications.
			 */
			observe();
			return inner.nextIndex();
		}
		@Override public T previous() {
			observe();
			return inner.previous();
		}
		@Override public int previousIndex() {
			observe();
			return inner.previousIndex();
		}
		@Override public void remove() {
			inner.remove();
			invalidate();
		}
		@Override public void set(T e) {
			Objects.requireNonNull(e);
			inner.set(e);
			invalidate();
		}
	}
	public static <T> List<T> list(List<T> list, Option... options) {
		Objects.requireNonNull(list);
		return new ReactiveList<>(list, options(options));
	}
	private static class ReactiveList<T> extends ReactiveCollection<T> implements List<T> {
		final List<T> inner;
		ReactiveList(List<T> inner, Options options) {
			super(inner, options);
			OwnerTrace.of(this).alias("list");
			this.inner = inner;
		}
		ReactiveList(List<T> inner, ReactiveCollectionObject master) {
			super(inner, master);
			OwnerTrace.of(this).alias("list");
			this.inner = inner;
		}
		@Override public boolean add(T item) {
			Objects.requireNonNull(item);
			inner.add(item);
			invalidate();
			return true;
		}
		@Override public void add(int index, T element) {
			Objects.requireNonNull(element);
			observeException();
			try {
				inner.add(index, element);
			} catch (IndexOutOfBoundsException ex) {
				throw silenceException(ex);
			}
			invalidate();
		}
		@Override public boolean addAll(Collection<? extends T> collection) {
			for (T item : collection)
				Objects.requireNonNull(item);
			boolean changed = inner.addAll(collection);
			invalidateIf(changed);
			/*
			 * Do not observe or silence the status, because it doesn't depend on collection state.
			 */
			return changed;
		}
		@Override public boolean addAll(int index, Collection<? extends T> collection) {
			for (T item : collection)
				Objects.requireNonNull(item);
			observeException();
			boolean changed;
			try {
				changed = inner.addAll(index, collection);
			} catch (IndexOutOfBoundsException ex) {
				throw silenceException(ex);
			}
			invalidateIf(changed);
			return changed;
		}
		@Override public T get(int index) {
			observe();
			return inner.get(index);
		}
		@Override public int indexOf(Object o) {
			observe();
			return inner.indexOf(o);
		}
		@Override public int lastIndexOf(Object o) {
			observe();
			return inner.lastIndexOf(o);
		}
		@Override public ListIterator<T> listIterator() {
			return new ReactiveListIterator<>(this, inner.listIterator());
		}
		@Override public ListIterator<T> listIterator(int index) {
			observeException();
			try {
				return new ReactiveListIterator<>(this, inner.listIterator(index));
			} catch (IndexOutOfBoundsException ex) {
				throw silenceException(ex);
			}
		}
		@Override public T remove(int index) {
			observeStatusAndException();
			T item;
			try {
				item = inner.remove(index);
			} catch (IndexOutOfBoundsException ex) {
				throw silenceException(ex);
			}
			invalidate();
			return silenceResult(item);
		}
		@Override public T set(int index, T element) {
			Objects.requireNonNull(element);
			observeStatusAndException();
			T previous;
			try {
				previous = inner.set(index, element);
			} catch (IndexOutOfBoundsException ex) {
				throw silenceException(ex);
			}
			invalidateIfChanged(previous, element);
			return silenceResult(previous);
		}
		@Override public List<T> subList(int fromIndex, int toIndex) {
			observeException();
			try {
				return new ReactiveList<>(inner.subList(fromIndex, toIndex), this);
			} catch (IndexOutOfBoundsException ex) {
				throw silenceException(ex);
			}
		}
	}
	public static <T> Set<T> set(Set<T> set, Option... options) {
		Objects.requireNonNull(set);
		return new ReactiveSet<>(set, options(options));
	}
	private static class ReactiveSet<T> extends ReactiveCollection<T> implements Set<T> {
		final Set<T> inner;
		ReactiveSet(Set<T> inner, Options options) {
			super(inner, options);
			OwnerTrace.of(this).alias("set");
			this.inner = inner;
		}
		ReactiveSet(Set<T> inner, ReactiveCollectionObject master) {
			super(inner, master);
			OwnerTrace.of(this).alias("set");
			this.inner = inner;
		}
		@Override public boolean add(T item) {
			Objects.requireNonNull(item);
			observeStatus();
			boolean changed = inner.add(item);
			invalidateIf(changed);
			return silenceStatus(changed);
		}
		@Override public boolean addAll(Collection<? extends T> collection) {
			for (T item : collection)
				Objects.requireNonNull(item);
			observeStatus();
			boolean changed = inner.addAll(collection);
			invalidateIf(changed);
			return silenceStatus(changed);
		}
	}
	public static <K, V> Map<K, V> map(Map<K, V> map, Option... options) {
		Objects.requireNonNull(map);
		return new ReactiveMap<>(map, options(options));
	}
	private static class ReactiveMap<K, V> extends ReactiveCollectionObject implements Map<K, V> {
		final Map<K, V> inner;
		ReactiveMap(Map<K, V> inner, Options options) {
			super(options);
			OwnerTrace.of(this).alias("map");
			this.inner = inner;
		}
		@Override public void clear() {
			inner.clear();
			invalidate();
		}
		@Override public boolean containsKey(Object key) {
			observe();
			return inner.containsKey(key);
		}
		@Override public boolean containsValue(Object value) {
			observe();
			return inner.containsValue(value);
		}
		@Override public Set<Entry<K, V>> entrySet() {
			return new ReactiveSet<>(inner.entrySet(), this);
		}
		@Override public boolean equals(Object obj) {
			observe();
			return inner.equals(obj);
		}
		@Override public V get(Object key) {
			observe();
			return inner.get(key);
		}
		@Override public int hashCode() {
			observe();
			return inner.hashCode();
		}
		@Override public boolean isEmpty() {
			observe();
			return inner.isEmpty();
		}
		@Override public Set<K> keySet() {
			return new ReactiveSet<>(inner.keySet(), this);
		}
		@Override public V put(K key, V value) {
			Objects.requireNonNull(value);
			observeStatus();
			V previous = inner.put(key, value);
			invalidateIfChanged(previous, value);
			return silenceResult(previous);
		}
		@Override public void putAll(Map<? extends K, ? extends V> m) {
			if (!m.isEmpty()) {
				for (V value : m.values())
					Objects.requireNonNull(value);
				inner.putAll(m);
				invalidate();
			}
		}
		@Override public V remove(Object key) {
			observeStatus();
			V value = inner.remove(key);
			invalidateIf(value != null);
			return silenceResult(value);
		}
		@Override public int size() {
			observe();
			return inner.size();
		}
		@Override public Collection<V> values() {
			return new ReactiveCollection<>(inner.values(), this);
		}
		@Override public String toString() {
			return OwnerTrace.of(this) + ": " + inner;
		}
	}
	public static <T> Queue<T> queue(Queue<T> queue, Option... options) {
		Objects.requireNonNull(queue);
		return new ReactiveQueue<>(queue, options(options));
	}
	/*
	 * Queue can be configured ignore/silence write status and exceptions,
	 * but it is usually a bad idea to do so since queue operations inherently combine reads with writes.
	 * It is better to rely on progress detection, which is done automatically.
	 */
	private static class ReactiveQueue<T> extends ReactiveCollection<T> implements Queue<T> {
		final Queue<T> inner;
		ReactiveQueue(Queue<T> inner, Options options) {
			super(inner, options);
			OwnerTrace.of(this).alias("queue");
			this.inner = inner;
		}
		@Override public T element() {
			observe();
			return inner.element();
		}
		@Override public boolean offer(T item) {
			Objects.requireNonNull(item);
			observeStatus();
			boolean changed = inner.offer(item);
			invalidateIf(changed);
			return silenceStatus(changed);
		}
		@Override public T peek() {
			observe();
			return inner.peek();
		}
		@Override public T poll() {
			observeStatus();
			T item = inner.poll();
			invalidateIf(item != null);
			return item;
		}
		@Override public T remove() {
			observeException();
			T item;
			try {
				item = inner.remove();
			} catch (NoSuchElementException ex) {
				throw silenceException(ex);
			}
			/*
			 * We only get here if exception is not thrown.
			 * In that case, progress has been made and it is okay to observe and invalidate at the same time.
			 */
			invalidate();
			return item;
		}
	}
}
