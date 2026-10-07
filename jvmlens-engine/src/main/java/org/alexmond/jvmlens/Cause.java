package org.alexmond.jvmlens;

/**
 * The suspected-cause heuristic: one hedged line naming the dominant signal of a
 * recording. Pure — it weighs already-aggregated magnitudes — so it is unit-tested on its
 * own, apart from the JFR reduction in {@link Summarizer}.
 */
final class Cause {

	private Cause() {
	}

	/**
	 * Pick the single suspected-cause line, weighting each dimension by
	 * <em>magnitude</em> rather than mere presence. A lock is the headline only when its
	 * measured blocked time is substantial <em>and</em> exceeds the estimated CPU work
	 * and GC — so a small lock no longer outranks a real CPU hot path or large allocation
	 * (field-finding #67); a present but secondary lock is demoted to a hedged trailing
	 * note. {@code estCpuMs} is a rough estimate (sample count × ~10 ms ExecutionSample
	 * period) — only the order-of-magnitude comparison matters here.
	 */
	static String suspected(Signals s) {
		boolean lockDominates = s.topLock() != null && s.lockMs() >= 100 && s.lockMs() >= s.estCpuMs()
				&& s.lockMs() >= s.gcMs();
		String lockNote = (s.topLock() != null && s.lockMs() >= 5 && !lockDominates)
				? " Minor lock contention in `" + s.topLock() + "` (" + s.lockMs() + " ms)." : "";
		if (lockDominates) {
			return "Lock contention — blocked time concentrated in `" + s.topLock() + "`"
					+ ((s.topMonitor() != null) ? " on a `" + s.topMonitor() + "` monitor." : ".");
		}
		if (s.gcMs() > 500 && s.topAlloc() != null) {
			return "High allocation pressure (GC paused " + s.gcMs() + " ms) — sustained allocation at `" + s.topAlloc()
					+ "`" + ((s.oldObjects() > 0) ? "; retained (old-object) samples suggest a leak." : ".") + lockNote;
		}
		if (s.topApp() != null && s.topAppShare() > 40) {
			return "CPU-bound — `" + s.topApp() + "` accounts for the majority of samples." + lockNote;
		}
		if (s.topPinned() != null && s.pinnedMs() > 100) {
			return "Virtual-thread pinning — carrier threads pinned at `" + s.topPinned()
					+ "`; a synchronized block or native call is blocking the carrier." + lockNote;
		}
		if (s.topApp() == null && s.topIo() != null) {
			return "I/O-bound — blocked time concentrated on `" + s.topIo() + "`." + lockNote;
		}
		if (s.topApp() != null) {
			String alloc = (s.topAlloc() != null && s.allocMb() >= 50) ? "; top allocation at `" + s.topAlloc() + "`"
					: "";
			return "Hot path is `" + s.topApp() + "`" + alloc + "." + lockNote;
		}
		return "No dominant signal." + lockNote;
	}

	/**
	 * The inputs the suspected-cause heuristic weighs, in comparable magnitudes (measured
	 * lock/GC/pinning in ms, allocation in MB, a rough CPU-work estimate in ms).
	 */
	record Signals(long lockMs, long gcMs, long allocMb, long estCpuMs, long pinnedMs, long oldObjects, String topApp,
			double topAppShare, String topAlloc, String topLock, String topMonitor, String topIo, String topPinned) {
	}

}
