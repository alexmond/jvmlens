package org.alexmond.jvmlens.deadlock;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

/**
 * Authoritative, in-process deadlock detection via {@link ThreadMXBean}. Unlike ordinary
 * lock contention — which JFR measures as <em>blocked time</em> — a true deadlock is a
 * cycle of threads that block <em>forever</em>, so the threads never acquire the monitor
 * and JFR's {@code JavaMonitorEnter} never fires. {@code findDeadlockedThreads()} is the
 * reliable signal, but it only sees the JVM it runs in, so this is an agent-side check.
 *
 * <p>
 * Dependency-free ({@code java.lang.management} only). Renders the deadlocked threads and
 * their wait-for edges as the {@code deadlock} extended section.
 */
public final class DeadlockDetector {

	/** Frames of each deadlocked thread's stack to look at (0 keeps the call cheap). */
	private static final int STACK_DEPTH = 0;

	private DeadlockDetector() {
	}

	/** Deadlock check against the current JVM's threads. */
	public static List<Section> detect() {
		return detect(ManagementFactory.getThreadMXBean());
	}

	/**
	 * Deadlock check against a given {@link ThreadMXBean} (the parameter makes it
	 * testable).
	 * @param threads the thread MXBean to query
	 * @return a single-element {@code deadlock} section, or empty when there is no
	 * deadlock
	 */
	public static List<Section> detect(ThreadMXBean threads) {
		long[] ids = threads.findDeadlockedThreads();
		if (ids == null || ids.length == 0) {
			return List.of();
		}
		List<Ranked> rows = new ArrayList<>();
		for (ThreadInfo info : threads.getThreadInfo(ids, STACK_DEPTH)) {
			if (info != null) {
				rows.add(new Ranked(info.getThreadName(), 1.0, 1, edge(info)));
			}
		}
		if (rows.isEmpty()) {
			return List.of();
		}
		return List.of(new Section("deadlock", "Deadlocked threads (wait-for cycle)", null, true, rows));
	}

	/**
	 * "waiting on &lt;lock&gt; held by &lt;owner&gt;" — the thread's edge in the cycle.
	 */
	private static String edge(ThreadInfo info) {
		StringBuilder b = new StringBuilder("waiting on ").append(info.getLockName());
		if (info.getLockOwnerName() != null) {
			b.append(" held by ").append(info.getLockOwnerName());
		}
		return b.toString();
	}

}
