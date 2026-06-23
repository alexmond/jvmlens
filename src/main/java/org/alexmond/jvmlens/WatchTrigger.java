package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A dump-on-trigger condition for {@code watch}: thresholds that, when breached by a
 * rolling-window {@link ProfileSummary}, mark the window worth surfacing. Each threshold
 * is disabled when {@code <= 0}. Computed purely from the structured summary so
 * {@code watch} stays an external observer.
 *
 * @param gcMillis fire when total GC pause time in the window reaches this many ms
 * @param cpuShare fire when the top hot path's sample share reaches this fraction (0..1)
 * @param oldObjects fire when retained (old-object) samples reach this count
 */
public record WatchTrigger(long gcMillis, double cpuShare, long oldObjects) {

	/** Whether any threshold is set (i.e. {@code watch} should emit only on breach). */
	public boolean active() {
		return this.gcMillis > 0 || this.cpuShare > 0 || this.oldObjects > 0;
	}

	/**
	 * The breached thresholds for this window, or {@code null} if none fired.
	 * @param s the rolling-window summary
	 * @return a human-readable reason, or {@code null}
	 */
	public String reason(ProfileSummary s) {
		List<String> hits = new ArrayList<>();
		if (this.gcMillis > 0 && s.gcPauseMillis() >= this.gcMillis) {
			hits.add("GC pause " + s.gcPauseMillis() + "ms ≥ " + this.gcMillis + "ms");
		}
		double topCpu = s.hotPaths().isEmpty() ? 0.0 : s.hotPaths().get(0).share();
		if (this.cpuShare > 0 && topCpu >= this.cpuShare) {
			hits.add(String.format(Locale.ROOT, "hot path %.0f%% ≥ %.0f%%", topCpu * 100, this.cpuShare * 100));
		}
		if (this.oldObjects > 0 && s.oldObjects() >= this.oldObjects) {
			hits.add(s.oldObjects() + " old-object samples ≥ " + this.oldObjects);
		}
		return hits.isEmpty() ? null : String.join("; ", hits);
	}

}
