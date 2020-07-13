// Part of Hookless: https://hookless.machinezoo.com
package com.machinezoo.hookless.time;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class AlarmScheduler {
	private Instant now = Instant.now();
	private AlarmIndex index = new AlarmIndex();
	private ScheduledFuture<?> future;
	private Instant schedule = Instant.MAX;
	static final AlarmScheduler instance = new AlarmScheduler();
	private static final Duration poll = Duration.ofSeconds(1);
	private static final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable);
			thread.setDaemon(true);
			thread.setName("hookless-timing");
			return thread;
		}
	});
	synchronized void monitor(ReactiveAlarm alarm, ReactiveAlarm previous) {
		if (alarm.lower == null && alarm.upper == null || alarm.lower != null && alarm.upper != null && alarm.lower.isAfter(alarm.upper))
			throw new IllegalArgumentException();
		/*
		 * Update 'now' before performing calculations below.
		 */
		tick();
		if (previous != null)
			index.remove(previous);
		/*
		 * If the current time is already outside of the alarm's bounds, signal it now.
		 * Don't allow invalid alarms in the index. They might be never signaled.
		 */
		if (alarm.lower != null && !reached(alarm.lower) || alarm.upper != null && reached(alarm.upper))
			alarm.ring();
		else {
			index.add(alarm);
			reschedule();
		}
	}
	private boolean reached(Instant time) {
		return !now.isBefore(time);
	}
	private void tick() {
		Instant fresh = Instant.now();
		int direction = fresh.compareTo(now);
		if (direction == 0)
			return;
		/*
		 * All indexed alarms have now in range [lower,upper).
		 * We want to invalidate alarms that don't have fresh in [lower,upper).
		 * Consider two cases:
		 * 1. fresh > now:
		 * We want to invalidate alarms with fresh in range [upper,inf).
		 * That means upper is in range (now,fresh].
		 * 2. fresh < now:
		 * We want to invalidate alarms with fresh in range (inf,lower).
		 * That means lower is in range (fresh,now].
		 * Since SortedSet performs lookups in ranges like [from,to),
		 * we have to modify the above ranges to [now+1,fresh+1) and [fresh+1,now+1) respectively.
		 */
		Instant now1 = now.plusNanos(1);
		Instant fresh1 = fresh.plusNanos(1);
		List<Instant> range = new ArrayList<>(direction > 0 ? index.sorted().subSet(now1, fresh1) : index.sorted().subSet(fresh1, now1));
		for (Instant time : range) {
			for (ReactiveAlarm alarm : index.at(time)) {
				index.remove(alarm);
				alarm.ring();
			}
		}
		now = fresh;
	}
	private void reschedule() {
		/*
		 * We want to run when the next alarm is invalidated, i.e. when its upper bound is reached.
		 * We therefore want to pick the first indexed time in range (now,inf) or [now+1,inf).
		 */
		SortedSet<Instant> pending = index.sorted().tailSet(now.plusNanos(1));
		Instant target;
		if (pending.isEmpty()) {
			target = now.plus(poll);
		} else {
			Instant first = pending.first();
			if (Duration.between(now, first).compareTo(poll) > 0)
				target = now.plus(poll);
			else
				target = first;
		}
		if (future != null) {
			if (target.plusMillis(1).compareTo(schedule) >= 0)
				return;
			future.cancel(false);
			future = null;
		}
		schedule = target;
		future = executor.schedule(this::expire, Math.max(1, Duration.between(now, schedule).toMillis()), TimeUnit.MILLISECONDS);
	}
	private synchronized void expire() {
		future = null;
		tick();
		reschedule();
	}
}
