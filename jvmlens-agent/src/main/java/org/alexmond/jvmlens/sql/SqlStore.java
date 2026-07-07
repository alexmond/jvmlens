package org.alexmond.jvmlens.sql;

import org.alexmond.jvmlens.probe.FailGuard;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

	/** Cap the per-execution stack walk so call-site capture stays cheap. */
	private static final int MAX_WALK = 40;

	private static final Map<String, Stat> SHAPES = new ConcurrentHashMap<>();

	/**
	 * Application-frame prefixes; when set (from the agent's scope) each execution's
	 * first matching stack frame is captured as the statement's call-site anchor. Empty =
	 * no walk, so there is zero added cost until a scope is configured.
	 */
	private static final AtomicReference<String[]> APP_PREFIXES = new AtomicReference<>(new String[0]);

	private SqlStore() {
	}

	/**
	 * Set the application-frame prefixes used to anchor each statement to its call-site
	 * (the agent refreshes this from the live {@code scope}); {@code null}/empty disables
	 * the walk.
	 */
	public static void setAppScope(List<String> prefixes) {
		APP_PREFIXES.set((prefixes != null) ? prefixes.toArray(new String[0]) : new String[0]);
	}

	/** Record one execution of {@code sql} taking {@code nanos}; called from advice. */
	public static void record(String sql, long nanos) {
		FailGuard.run("db",
				() -> SHAPES.computeIfAbsent(SqlSanitizer.sanitize(sql), (k) -> new Stat()).add(nanos, callSite()));
	}

	/**
	 * The first application stack frame ({@code com.acme.OrderRepo:88}) issuing the
	 * current statement, or {@code null} when no scope is set or no app frame carries a
	 * line — the anchor that lets {@code analyze --source} echo the offending line and a
	 * coding agent jump straight to the query.
	 */
	private static String callSite() {
		String[] prefixes = APP_PREFIXES.get();
		if (prefixes.length == 0) {
			return null;
		}
		return StackWalker.getInstance()
			.walk((frames) -> frames.limit(MAX_WALK)
				.filter((f) -> f.getLineNumber() > 0 && startsWithAny(f.getClassName(), prefixes))
				.map((f) -> f.getClassName() + ":" + f.getLineNumber())
				.findFirst()
				.orElse(null));
	}

	private static boolean startsWithAny(String owner, String[] prefixes) {
		for (String prefix : prefixes) {
			if (owner.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** Clear all captured statements and scope (used by tests). */
	public static void reset() {
		SHAPES.clear();
		APP_PREFIXES.set(new String[0]);
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

		void add(long ns, String site) {
			this.calls.incrementAndGet();
			this.nanos.addAndGet(Math.max(ns, 0));
			if (site != null) {
				this.sites.computeIfAbsent(site, (k) -> new AtomicLong()).incrementAndGet();
			}
		}

		/** The most frequent captured call-site, or {@code null} if none was captured. */
		private String dominantSite() {
			return this.sites.entrySet()
				.stream()
				.max(Map.Entry.comparingByValue((a, b) -> Long.compare(a.get(), b.get())))
				.map(Map.Entry::getKey)
				.orElse(null);
		}

		String teaser() {
			long c = this.calls.get();
			double avgMs = (c > 0) ? (this.nanos.get() / 1_000_000.0 / c) : 0;
			StringBuilder base = new StringBuilder(String.format(Locale.ROOT, "%d calls, avg %.1f ms", c, avgMs));
			String site = dominantSite();
			if (site != null) {
				base.append(" · at ").append(site);
			}
			if (c >= N_PLUS_ONE_CALLS && avgMs < N_PLUS_ONE_AVG_MS) {
				base.append(" — high call count, possible N+1");
			}
			return base.toString();
		}

	}

}
