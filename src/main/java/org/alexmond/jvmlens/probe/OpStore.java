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

	/** How many operations a section keeps. */
	private static final int TOP_N = 5;

	private final Map<String, Stat> ops = new ConcurrentHashMap<>();

	/** Record one operation {@code label} taking {@code nanos}. */
	public void record(String label, long nanos) {
		this.ops.computeIfAbsent(shorten(label), (k) -> new Stat()).add(nanos);
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
		if (this.ops.isEmpty()) {
			return List.of();
		}
		long total = this.ops.values().stream().mapToLong((st) -> st.nanos.get()).sum();
		List<Ranked> rows = this.ops.entrySet()
			.stream()
			.sorted((a, b) -> Long.compare(b.getValue().nanos.get(), a.getValue().nanos.get()))
			.limit(TOP_N)
			.map((en) -> new Ranked(en.getKey(), (total > 0) ? (double) en.getValue().nanos.get() / total : 0,
					en.getValue().nanos.get(), en.getValue().teaser()))
			.toList();
		return List.of(new Section(key, title, "ms", true, rows));
	}

	/** Reduce a {@code fully.qualified.Type.method} label to {@code Type.method}. */
	static String shorten(String label) {
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
			return String.format(Locale.ROOT, "%d ops, avg %.1f ms", c, avgMs);
		}

	}

}
