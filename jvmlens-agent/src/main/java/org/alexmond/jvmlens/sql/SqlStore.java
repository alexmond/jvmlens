package org.alexmond.jvmlens.sql;

import org.alexmond.jvmlens.probe.CallSites;
import org.alexmond.jvmlens.probe.FailGuard;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

/**
 * Accumulates JDBC executions captured by {@code SqlAdvice}, aggregated by sanitized SQL
 * shape, and renders them as the {@code db} extended {@link Section}: top statements by
 * total time, each with call count, average latency, and a hedged N+1 flag. Static
 * because the inlined advice references it; values are bounded counters, so it never pins
 * the target's heap.
 */
public final class SqlStore {

	/** A shape executed at least this many times with a low average is flagged N+1. */
	private static final long N_PLUS_ONE_CALLS = 50;

	/**
	 * Average latency (ms) below which a high-count shape reads as N+1, not one slow
	 * query.
	 */
	private static final double N_PLUS_ONE_AVG_MS = 5.0;

	private static final Map<String, Stat> SHAPES = new ConcurrentHashMap<>();

	private SqlStore() {
	}

	/** Record one execution of {@code sql} taking {@code nanos}; called from advice. */
	public static void record(String sql, long nanos) {
		FailGuard.run("db", () -> {
			List<String> path = CallSites.capturePath();
			SHAPES.computeIfAbsent(SqlSanitizer.sanitize(sql), (k) -> new Stat())
				.add(nanos, CallSites.site(path), CallSites.entryClass(path));
		});
	}

	/** Clear all captured statements and scope (used by tests). */
	public static void reset() {
		SHAPES.clear();
		CallSites.reset();
	}

	/** The captured statements as the {@code db} section, or empty if nothing ran. */
	public static List<Section> sections() {
		if (SHAPES.isEmpty()) {
			return List.of();
		}
		long total = SHAPES.values().stream().mapToLong((st) -> st.nanos.get()).sum();
		List<Ranked> rows = SHAPES.entrySet()
			.stream()
			.sorted((a, b) -> Long.compare(b.getValue().nanos.get(), a.getValue().nanos.get()))
			.limit(org.alexmond.jvmlens.RankLimits.limit("db"))
			.map((en) -> new Ranked(en.getKey(), (total > 0) ? (double) en.getValue().nanos.get() / total : 0,
					en.getValue().nanos.get(), en.getValue().teaser()))
			.toList();
		return List.of(new Section("db", "Top SQL (by total time, sanitized)", "ms", true, rows));
	}

	private static final class Stat {

		private final AtomicLong calls = new AtomicLong();

		private final AtomicLong nanos = new AtomicLong();

		private final Map<String, AtomicLong> sites = new ConcurrentHashMap<>();

		private final Map<String, AtomicLong> entries = new ConcurrentHashMap<>();

		void add(long ns, String site, String entry) {
			this.calls.incrementAndGet();
			this.nanos.addAndGet(Math.max(ns, 0));
			if (site != null) {
				this.sites.computeIfAbsent(site, (k) -> new AtomicLong()).incrementAndGet();
			}
			if (entry != null) {
				this.entries.computeIfAbsent(entry, (k) -> new AtomicLong()).incrementAndGet();
			}
		}

		/** The most frequent value in {@code counts}, or {@code null} if none. */
		private String dominant(Map<String, AtomicLong> counts) {
			return counts.entrySet()
				.stream()
				.max(Map.Entry.comparingByValue((a, b) -> Long.compare(a.get(), b.get())))
				.map(Map.Entry::getKey)
				.orElse(null);
		}

		String teaser() {
			long c = this.calls.get();
			double avgMs = (c > 0) ? (this.nanos.get() / 1_000_000.0 / c) : 0;
			StringBuilder base = new StringBuilder(String.format(Locale.ROOT, "%d calls, avg %.1f ms", c, avgMs));
			String site = dominant(this.sites);
			if (site != null) {
				base.append(" · at ").append(site);
			}
			String entry = dominant(this.entries);
			if (entry != null) {
				base.append(" ↳ under ").append(entry);
			}
			if (c >= N_PLUS_ONE_CALLS && avgMs < N_PLUS_ONE_AVG_MS) {
				base.append(" — high call count, possible N+1");
			}
			return base.toString();
		}

	}

}
