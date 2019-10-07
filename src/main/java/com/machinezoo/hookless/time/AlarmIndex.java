// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import static java.util.stream.Collectors.*;
import java.time.*;
import java.util.*;

/*
 * Helper to let us easily find all active ReactiveAlarm instances with one of their bounds inside some time range.
 * Locking is done on AlarmScheduler level.
 */
class AlarmIndex {
	/*
	 * TreeMap lets us find times in a range. At this level, we don't care that alarm consists of two times.
	 * There can be more than one alarm associated with time, so we store them in a set.
	 * Alarms are stored as weak references, so that this index doesn't prevent GC.
	 */
	private NavigableMap<Instant, Set<ReactiveAlarm>> sorted = new TreeMap<>();
	/*
	 * Since we have weak values, these values can randomly disappear as GC collects them.
	 * We need to periodically purge the tree of all entries with zero values.
	 * We could have used specialized data structure from a library,
	 * but I couldn't quickly find one and regular purging is simple enough.
	 */
	private int purgeAt = 1;
	private void purge() {
		if (sorted.size() >= purgeAt) {
			List<Instant> purged = sorted.entrySet().stream()
				.filter(e -> e.getValue().isEmpty())
				.map(e -> e.getKey())
				.collect(toList());
			for (Instant time : purged)
				sorted.remove(time);
			purgeAt = 2 * sorted.size() + 1;
		}
	}
	/*
	 * Alarm is always added and removed whole with both lower and upper bound.
	 */
	void add(ReactiveAlarm alarm) {
		if (alarm.lower != null)
			add(alarm.lower, alarm);
		if (alarm.upper != null)
			add(alarm.upper, alarm);
		purge();
	}
	private void add(Instant time, ReactiveAlarm alarm) {
		Set<ReactiveAlarm> alarms = sorted.get(time);
		if (alarms == null) {
			/*
			 * Weak set to allow GC to collect the alarms.
			 */
			sorted.put(time, alarms = Collections.newSetFromMap(new WeakHashMap<ReactiveAlarm, Boolean>()));
		}
		alarms.add(alarm);
	}
	void remove(ReactiveAlarm alarm) {
		if (alarm.lower != null)
			remove(alarm.lower, alarm);
		if (alarm.upper != null)
			remove(alarm.upper, alarm);
	}
	private void remove(Instant time, ReactiveAlarm alarm) {
		Set<ReactiveAlarm> alarms = sorted.get(time);
		if (alarms != null) {
			alarms.remove(alarm);
			if (alarms.isEmpty())
				sorted.remove(time);
		}
	}
	SortedSet<Instant> sorted() {
		return sorted.navigableKeySet();
	}
	List<ReactiveAlarm> at(Instant time) {
		Set<ReactiveAlarm> alarms = sorted.get(time);
		if (alarms == null)
			return Collections.emptyList();
		return new ArrayList<>(alarms);
	}
}
