package org.alexmond.jvmlens.snapshot;

import org.alexmond.jvmlens.probe.FailGuard;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Accumulates the variable-snapshot values captured by {@link SnapshotAdvice} at each
 * instrumented method, and renders them as an LLM-ready section: per call site, the call
 * count and a per-argument value digest (distinct values capped, null rate, numeric
 * range).
 *
 * <p>
 * Static because the inlined advice references it directly. Values are stored as bounded
 * string digests (not object references), so it never pins the target's heap.
 */
public final class SnapshotStore {

	private static final int MAX_DISTINCT = 20;

	private static final int MAX_VALUE_LEN = 60;

	private static final Map<String, Site> SITES = new ConcurrentHashMap<>();

	private SnapshotStore() {
	}

	/** Record one method-entry observation; called from inlined advice on every call. */
	public static void record(String site, Object[] args) {
		FailGuard.run("snapshot", () -> SITES.computeIfAbsent(site, (k) -> new Site()).add(args));
	}

	/** Clear all captured snapshots (used by tests). */
	public static void reset() {
		SITES.clear();
	}

	/** The captured snapshots as a markdown section, or empty if nothing was captured. */
	public static String render() {
		if (SITES.isEmpty()) {
			return "";
		}
		StringBuilder md = new StringBuilder("## Variable snapshots\n");
		SITES.forEach((site, stats) -> stats.render(md, site));
		return md.toString();
	}

	private static final class Site {

		private final ReentrantLock lock = new ReentrantLock();

		private long calls;

		private List<ArgStat> args;

		void add(Object[] values) {
			this.lock.lock();
			try {
				this.calls++;
				if (this.args == null) {
					this.args = new ArrayList<>();
					for (Object ignored : values) {
						this.args.add(new ArgStat());
					}
				}
				for (int i = 0; i < values.length && i < this.args.size(); i++) {
					this.args.get(i).add(values[i]);
				}
			}
			finally {
				this.lock.unlock();
			}
		}

		void render(StringBuilder md, String site) {
			this.lock.lock();
			try {
				md.append("### `").append(site).append("` — ").append(this.calls).append(" calls\n");
				if (this.args == null || this.args.isEmpty()) {
					md.append("- (no arguments)\n");
				}
				else {
					for (int i = 0; i < this.args.size(); i++) {
						md.append("- arg").append(i).append(": ");
						this.args.get(i).render(md);
						md.append('\n');
					}
				}
				md.append('\n');
			}
			finally {
				this.lock.unlock();
			}
		}

	}

	private static final class ArgStat {

		private final Set<String> distinct = new LinkedHashSet<>();

		private long nulls;

		private boolean truncated;

		private boolean allNumbers = true;

		private boolean sawNumber;

		private double min = Double.POSITIVE_INFINITY;

		private double max = Double.NEGATIVE_INFINITY;

		void add(Object value) {
			if (value == null) {
				this.nulls++;
				return;
			}
			if (value instanceof Number number) {
				this.sawNumber = true;
				double d = number.doubleValue();
				this.min = Math.min(this.min, d);
				this.max = Math.max(this.max, d);
			}
			else {
				this.allNumbers = false;
			}
			if (this.distinct.size() < MAX_DISTINCT) {
				String s = String.valueOf(value);
				this.distinct.add((s.length() > MAX_VALUE_LEN) ? s.substring(0, MAX_VALUE_LEN) + "…" : s);
			}
			else {
				this.truncated = true;
			}
		}

		void render(StringBuilder md) {
			md.append(this.distinct.size())
				.append(this.truncated ? "+" : "")
				.append(" distinct ")
				.append(this.distinct);
			if (this.allNumbers && this.sawNumber) {
				md.append(String.format(Locale.ROOT, " (range %.0f..%.0f)", this.min, this.max));
			}
			if (this.nulls > 0) {
				md.append(", ").append(this.nulls).append(" null");
			}
		}

	}

}
