package org.alexmond.jvmlens.probe;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

/**
 * A reusable operation-timing aggregator for the agent's instrumentation dimensions
 * (messaging, cache, …): groups calls by a short {@code Class.method} label and renders
 * the top operations by total time, each with a call count and average latency.
 * Thread-safe and bounded (counters only), so it never pins the target's heap.
 */
public final class OpStore {

	private final Map<String, Stat> ops = new ConcurrentHashMap<>();

	/** Record one operation {@code label} taking {@code nanos}. */
	public void record(String label, long nanos) {
		record(label, nanos, null, null);
	}

	/**
	 * Record one operation {@code label} taking {@code nanos}, attributed to app
	 * call-site {@code site} ({@code null} = none captured).
	 */
	public void record(String label, long nanos, String site) {
		record(label, nanos, site, null);
	}

	/**
	 * As {@link #record(String, long, String)}, but also records the op's outermost app
	 * caller {@code entry} (the request entry) for P2b shared-call-path linkage;
	 * {@code null} when the op has no app frame above its anchor.
	 */
	public void record(String label, long nanos, String site, String entry) {
		this.ops.computeIfAbsent(shorten(label), (k) -> new Stat()).add(nanos, site, entry);
	}

	/** Clear all captured operations (used by tests). */
	public void reset() {
		this.ops.clear();
	}

	/**
	 * The captured operations as one extended section, or empty if nothing ran.
	 * @param key the section key (e.g. {@code messaging}, {@code cache})
	 * @param title the section heading
	 * @return a single-element list, or empty
	 */
	public List<Section> sections(String key, String title) {
		return sections(key, title, (label, calls, nanos) -> "");
	}

	/**
	 * As {@link #sections(String, String)}, but each row's teaser is enriched with
	 * {@code flag} — a dimension-specific hedged note computed from the op's own stats
	 * (keeps this store generic while letting messaging/cache add their own signal).
	 * @param key the section key
	 * @param title the section heading
	 * @param flag the per-op flag (returns {@code ""} for none)
	 * @return a single-element list, or empty
	 */
	public List<Section> sections(String key, String title, OpFlag flag) {
		if (this.ops.isEmpty()) {
			return List.of();
		}
		long total = this.ops.values().stream().mapToLong((st) -> st.nanos.get()).sum();
		List<Ranked> rows = this.ops.entrySet()
			.stream()
			.sorted((a, b) -> Long.compare(b.getValue().nanos.get(), a.getValue().nanos.get()))
			.limit(org.alexmond.jvmlens.RankLimits.limit(key))
			.map((en) -> {
				Stat st = en.getValue();
				String teaser = st.teaser() + flag.apply(en.getKey(), st.calls.get(), st.nanos.get());
				return new Ranked(en.getKey(), (total > 0) ? (double) st.nanos.get() / total : 0, st.nanos.get(),
						teaser);
			})
			.toList();
		return List.of(new Section(key, title, "ms", true, rows));
	}

	/** Reduce a {@code fully.qualified.Type.method} label to {@code Type.method}. */
	public static String shorten(String label) {
		if (label == null || label.isBlank()) {
			return "?";
		}
		int method = label.lastIndexOf('.');
		if (method <= 0) {
			return label;
		}
		int type = label.lastIndexOf('.', method - 1);
		return (type < 0) ? label : label.substring(type + 1);
	}

	/**
	 * A per-op hedged flag (e.g. {@code — synchronous per-message send}) or {@code ""}.
	 */
	@FunctionalInterface
	public interface OpFlag {

		/**
		 * The flag suffix for an op, given its short label, call count, and total nanos.
		 */
		String apply(String label, long calls, long nanos);

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
			StringBuilder base = new StringBuilder(String.format(Locale.ROOT, "%d ops, avg %.1f ms", c, avgMs));
			String site = dominant(this.sites);
			if (site != null) {
				base.append(" · at ").append(site);
			}
			String entry = dominant(this.entries);
			if (entry != null) {
				base.append(" ↳ under ").append(entry);
			}
			return base.toString();
		}

	}

}
