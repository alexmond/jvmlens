package org.alexmond.jvmlens.web;

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
 * Accumulates HTTP requests captured by {@code WebAdvice}, aggregated by
 * {@code METHOD route-shape}, and renders them as the {@code web} extended
 * {@link Section}: top endpoints by total time, each with request count, average latency,
 * and an error count. Static (the inlined advice references it) and bounded (counters
 * only).
 */
public final class WebStore {

	private static final int SERVER_ERROR = 400;

	/**
	 * Below this request count a high error <em>rate</em> isn't statistically meaningful.
	 */
	private static final long HIGH_ERROR_MIN_REQS = 10;

	/** Error fraction at or above which an endpoint is flagged as erroring. */
	private static final double HIGH_ERROR_RATE = 0.2;

	private static final Map<String, Stat> ENDPOINTS = new ConcurrentHashMap<>();

	private WebStore() {
	}

	/** Record one request; called from advice. {@code status} 0 if unknown. */
	public static void record(String method, String path, int status, long nanos) {
		FailGuard.run("web", () -> {
			String key = ((method != null) ? method : "?") + " " + WebSanitizer.route(path);
			ENDPOINTS.computeIfAbsent(key, (k) -> new Stat()).add(nanos, status, CallSites.capture());
		});
	}

	/** Clear all captured endpoints and scope (used by tests). */
	public static void reset() {
		ENDPOINTS.clear();
		CallSites.reset();
	}

	/**
	 * The captured endpoints as the {@code web} section, or empty if nothing was served.
	 */
	public static List<Section> sections() {
		if (ENDPOINTS.isEmpty()) {
			return List.of();
		}
		long total = ENDPOINTS.values().stream().mapToLong((st) -> st.nanos.get()).sum();
		List<Ranked> rows = ENDPOINTS.entrySet()
			.stream()
			.sorted((a, b) -> Long.compare(b.getValue().nanos.get(), a.getValue().nanos.get()))
			.limit(org.alexmond.jvmlens.RankLimits.limit("web"))
			.map((en) -> new Ranked(en.getKey(), (total > 0) ? (double) en.getValue().nanos.get() / total : 0,
					en.getValue().nanos.get(), en.getValue().teaser()))
			.toList();
		return List.of(new Section("web", "Top HTTP endpoints (by total time)", "ms", true, rows));
	}

	private static final class Stat {

		private final AtomicLong calls = new AtomicLong();

		private final AtomicLong nanos = new AtomicLong();

		private final AtomicLong errors = new AtomicLong();

		private final Map<String, AtomicLong> sites = new ConcurrentHashMap<>();

		void add(long ns, int status, String site) {
			this.calls.incrementAndGet();
			this.nanos.addAndGet(Math.max(ns, 0));
			if (status >= SERVER_ERROR) {
				this.errors.incrementAndGet();
			}
			if (site != null) {
				this.sites.computeIfAbsent(site, (k) -> new AtomicLong()).incrementAndGet();
			}
		}

		/** The most frequent captured handler call-site, or {@code null} if none. */
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
			StringBuilder base = new StringBuilder(String.format(Locale.ROOT, "%d reqs, avg %.1f ms", c, avgMs));
			String site = dominantSite();
			if (site != null) {
				base.append(" · at ").append(site);
			}
			long e = this.errors.get();
			if (e > 0) {
				base.append(", ").append(e).append(" errors");
			}
			if (c >= HIGH_ERROR_MIN_REQS && (double) e / c >= HIGH_ERROR_RATE) {
				base.append(" — high error rate");
			}
			return base.toString();
		}

	}

}
