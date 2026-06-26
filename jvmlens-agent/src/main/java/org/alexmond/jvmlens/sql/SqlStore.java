package org.alexmond.jvmlens.sql;

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
		SHAPES.computeIfAbsent(SqlSanitizer.sanitize(sql), (k) -> new Stat()).add(nanos);
	}

	/** Clear all captured statements (used by tests). */
	public static void reset() {
		SHAPES.clear();
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

		void add(long ns) {
			this.calls.incrementAndGet();
			this.nanos.addAndGet(Math.max(ns, 0));
		}

		String teaser() {
			long c = this.calls.get();
			double avgMs = (c > 0) ? (this.nanos.get() / 1_000_000.0 / c) : 0;
			String base = String.format(Locale.ROOT, "%d calls, avg %.1f ms", c, avgMs);
			return (c >= N_PLUS_ONE_CALLS && avgMs < N_PLUS_ONE_AVG_MS) ? (base + " — high call count, possible N+1")
					: base;
		}

	}

}
