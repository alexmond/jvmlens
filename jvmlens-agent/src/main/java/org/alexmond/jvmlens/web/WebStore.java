package org.alexmond.jvmlens.web;

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

	private static final Map<String, Stat> ENDPOINTS = new ConcurrentHashMap<>();

	private WebStore() {
	}

	/** Record one request; called from advice. {@code status} 0 if unknown. */
	public static void record(String method, String path, int status, long nanos) {
		FailGuard.run("web", () -> {
			String key = ((method != null) ? method : "?") + " " + WebSanitizer.route(path);
			ENDPOINTS.computeIfAbsent(key, (k) -> new Stat()).add(nanos, status);
		});
	}

	/** Clear all captured endpoints (used by tests). */
	public static void reset() {
		ENDPOINTS.clear();
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

		void add(long ns, int status) {
			this.calls.incrementAndGet();
			this.nanos.addAndGet(Math.max(ns, 0));
			if (status >= SERVER_ERROR) {
				this.errors.incrementAndGet();
			}
		}

		String teaser() {
			long c = this.calls.get();
			double avgMs = (c > 0) ? (this.nanos.get() / 1_000_000.0 / c) : 0;
			String base = String.format(Locale.ROOT, "%d reqs, avg %.1f ms", c, avgMs);
			long e = this.errors.get();
			return (e > 0) ? (base + ", " + e + " errors") : base;
		}

	}

}
