package org.alexmond.jvmlens;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * Time-series support for the <em>long-running</em> monitor: instead of overwriting a
 * single summary each interval, a capture path appends one compact {@link Sample} per
 * interval (covering the three dimensions users feel — <em>CPU</em>, <em>memory</em>, and
 * <em>wait</em>), and {@link #digest(List)} reduces an accumulated run to an LLM-ready
 * <em>change-over-time</em> report. The per-line JSON is hand-rolled so the engine stays
 * dependency-free; the CLI reads it back with a mapper.
 */
public final class History {

	/** Below this many execution samples per window, the CPU dimension is noisy. */
	private static final int LOW_SAMPLE_THRESHOLD = 200;

	/**
	 * A dimension is "rising"/"falling" only past this fractional change (else "flat").
	 */
	private static final double TREND_BAND = 0.20;

	/** Old-object growth past this factor (first→last) hints at retention/leak. */
	private static final double LEAK_FACTOR = 2.0;

	private History() {
	}

	/**
	 * Build a compact history sample from a full summary stamped at {@code epochMillis}.
	 */
	public static Sample sample(ProfileSummary s, long epochMillis) {
		Ranked hot = first(s.hotPaths());
		Ranked alloc = first(s.allocSites());
		Ranked lock = first(s.locks());
		return new Sample(epochMillis, s.execSamples(), name(hot), share(hot), count(hot), s.gcPauses(),
				s.gcPauseMillis(), count(alloc), name(alloc), s.oldObjects(), name(lock), count(lock) / 1_000_000L,
				s.cause(), sectionMs(s, "io"), sectionMs(s, "pinning"), sectionMs(s, "db"), sectionMs(s, "web"));
	}

	/**
	 * Total blocked/pinned milliseconds across an extended section's rows (count is
	 * nanos).
	 */
	private static long sectionMs(ProfileSummary s, String key) {
		return s.sections()
			.stream()
			.filter((sec) -> sec.key().equals(key))
			.flatMap((sec) -> sec.rows().stream())
			.mapToLong(Ranked::count)
			.sum() / 1_000_000L;
	}

	/** One JSONL line for {@code sample(summary, t)} — what a capture path appends. */
	public static String toJsonLine(ProfileSummary s, long epochMillis) {
		return toJson(sample(s, epochMillis));
	}

	/** Serialize one sample as a flat JSON object (component names are the keys). */
	public static String toJson(Sample s) {
		StringBuilder j = new StringBuilder("{\"t\":");
		j.append(s.t());
		j.append(",\"exec\":").append(s.exec());
		j.append(",\"hot\":").append(str(s.hot()));
		j.append(",\"hotShare\":").append(String.format(Locale.ROOT, "%.4f", s.hotShare()));
		j.append(",\"hotCount\":").append(s.hotCount());
		j.append(",\"gcPauses\":").append(s.gcPauses());
		j.append(",\"gcMs\":").append(s.gcMs());
		j.append(",\"allocBytes\":").append(s.allocBytes());
		j.append(",\"alloc\":").append(str(s.alloc()));
		j.append(",\"oldObjects\":").append(s.oldObjects());
		j.append(",\"lock\":").append(str(s.lock()));
		j.append(",\"lockMs\":").append(s.lockMs());
		j.append(",\"cause\":").append(str(s.cause()));
		j.append(",\"ioMs\":").append(s.ioMs());
		j.append(",\"pinnedMs\":").append(s.pinnedMs());
		j.append(",\"dbMs\":").append(s.dbMs());
		j.append(",\"webMs\":").append(s.webMs());
		return j.append('}').toString();
	}

	/** Re-emit a run as a JSON array of samples (the {@code trend -f json} rendering). */
	public static String toJsonArray(List<Sample> samples) {
		StringBuilder j = new StringBuilder("[");
		for (int i = 0; i < samples.size(); i++) {
			j.append((i == 0) ? "\n  " : ",\n  ").append(toJson(samples.get(i)));
		}
		return j.append(samples.isEmpty() ? "]\n" : "\n]\n").toString();
	}

	/**
	 * Reduce an accumulated run to a change-over-time markdown report across the CPU,
	 * memory, and wait dimensions, plus a hedged retention indicator. Under-interprets:
	 * it reports how signals moved, never a confident "leak".
	 * @param samples the run's samples (any order; sorted by timestamp here)
	 * @return LLM-ready markdown
	 */
	public static String digest(List<Sample> samples) {
		List<Sample> run = samples.stream().sorted((a, b) -> Long.compare(a.t(), b.t())).toList();
		StringBuilder md = new StringBuilder("# JVM long-run trend\n\n");
		if (run.size() < 2) {
			return md.append("> Need at least 2 history points for a trend; have ")
				.append(run.size())
				.append(".\n")
				.toString();
		}
		Sample lo = run.get(0);
		Sample hi = run.get(run.size() - 1);
		long spanMs = hi.t() - lo.t();
		md.append("Window: ")
			.append(run.size())
			.append(" snapshots over ")
			.append(humanDuration(spanMs))
			.append(".\n\n");
		appendCpu(md, run);
		appendMemory(md, run);
		appendWait(md, run);
		appendApplication(md, run);
		appendRetention(md, run);
		return md.toString();
	}

	private static void appendCpu(StringBuilder md, List<Sample> run) {
		double execAvg = run.stream().mapToLong(Sample::exec).average().orElse(0);
		md.append("## CPU [sampled]\n");
		Map<String, Long> hotByCount = new LinkedHashMap<>();
		for (Sample s : run) {
			if (s.hot() != null && !s.hot().isEmpty()) {
				hotByCount.merge(s.hot(), 1L, Long::sum);
			}
		}
		if (hotByCount.isEmpty()) {
			md.append("- No application hot path surfaced in any window.\n");
		}
		else if (hotByCount.size() == 1) {
			md.append("- Stable hot path `")
				.append(hotByCount.keySet().iterator().next())
				.append("` dominated every window.\n");
		}
		else {
			String top = hotByCount.entrySet()
				.stream()
				.max(Map.Entry.comparingByValue())
				.map(Map.Entry::getKey)
				.orElse("?");
			String latest = run.get(run.size() - 1).hot();
			md.append("- Hot path shifted across ")
				.append(hotByCount.size())
				.append(" methods (most-frequent `")
				.append(top)
				.append("`, latest `")
				.append(latest)
				.append("`).\n");
		}
		md.append("- Exec-sample volume ").append(trendLine(run, Sample::exec));
		if (execAvg < LOW_SAMPLE_THRESHOLD) {
			md.append("- ⚠ Avg ")
				.append(String.format(Locale.ROOT, "%.0f", execAvg))
				.append(" exec samples/window — CPU shares are statistically noisy.\n");
		}
		md.append('\n');
	}

	private static void appendMemory(StringBuilder md, List<Sample> run) {
		md.append("## Memory [sampled + measured]\n- Allocation (top-site bytes) ")
			.append(trendLine(run, Sample::allocBytes, History::humanBytes));
		md.append("- GC pause time/window ").append(trendLine(run, Sample::gcMs));
		md.append("- Retained (old-object) samples ").append(trendLine(run, Sample::oldObjects));
		md.append('\n');
	}

	private static void appendWait(StringBuilder md, List<Sample> run) {
		md.append("## Wait / I/O / pinning [measured]\n");
		long lockWindows = run.stream().filter((s) -> s.lock() != null && !s.lock().isEmpty()).count();
		if (lockWindows == 0) {
			md.append("- No lock contention measured in any window.\n");
		}
		else {
			Sample worst = run.stream().max((a, b) -> Long.compare(a.lockMs(), b.lockMs())).orElse(run.get(0));
			md.append("- Contention in ")
				.append(lockWindows)
				.append('/')
				.append(run.size())
				.append(" windows; worst `")
				.append(worst.lock())
				.append("` at ")
				.append(worst.lockMs())
				.append(" ms.\n- Blocked time/window ")
				.append(trendLine(run, Sample::lockMs));
		}
		if (run.stream().anyMatch((s) -> s.ioMs() > 0)) {
			md.append("- External I/O blocked time/window ").append(trendLine(run, Sample::ioMs));
		}
		if (run.stream().anyMatch((s) -> s.pinnedMs() > 0)) {
			md.append("- Virtual-thread pinning time/window ").append(trendLine(run, Sample::pinnedMs));
		}
		md.append('\n');
	}

	private static void appendApplication(StringBuilder md, List<Sample> run) {
		boolean anyDb = run.stream().anyMatch((s) -> s.dbMs() > 0);
		boolean anyWeb = run.stream().anyMatch((s) -> s.webMs() > 0);
		if (!anyDb && !anyWeb) {
			return;
		}
		md.append("## Application (web / db) [measured]\n");
		if (anyWeb) {
			md.append("- HTTP time/window ").append(trendLine(run, Sample::webMs));
		}
		if (anyDb) {
			md.append("- SQL time/window ").append(trendLine(run, Sample::dbMs));
		}
		md.append('\n');
	}

	private static void appendRetention(StringBuilder md, List<Sample> run) {
		long firstOld = run.get(0).oldObjects();
		long lastOld = run.get(run.size() - 1).oldObjects();
		double firstGc = avgThird(run, Sample::gcMs, true);
		double lastGc = avgThird(run, Sample::gcMs, false);
		boolean oldGrew = lastOld >= firstOld + 5 && lastOld >= Math.max(1, firstOld) * LEAK_FACTOR;
		boolean gcGrew = lastGc >= firstGc * (1 + TREND_BAND) && lastGc > 0;
		md.append("## Retention indicator (heuristic)\n");
		if (oldGrew && gcGrew) {
			md.append("- ⚠ Old-object samples grew (")
				.append(firstOld)
				.append(" → ")
				.append(lastOld)
				.append(") while GC pressure rose — *possible* retention growth; confirm with an allocation-site"
						+ " diff, do not assume a leak.\n");
		}
		else if (oldGrew) {
			md.append("- Old-object samples grew (")
				.append(firstOld)
				.append(" → ")
				.append(lastOld)
				.append(") but GC pressure did not clearly rise — weak retention signal.\n");
		}
		else {
			md.append("- No sustained retention growth across the run.\n");
		}
	}

	/** A "first-third → last-third" trend sentence for a numeric series. */
	private static String trendLine(List<Sample> run, java.util.function.ToLongFunction<Sample> field) {
		return trendLine(run, field, (v) -> String.format(Locale.ROOT, "%.0f", v));
	}

	/** As {@link #trendLine}, but rendering the endpoints with a custom formatter. */
	private static String trendLine(List<Sample> run, java.util.function.ToLongFunction<Sample> field,
			java.util.function.DoubleFunction<String> fmt) {
		double first = avgThird(run, field, true);
		double last = avgThird(run, field, false);
		return direction(first, last) + " (" + fmt.apply(first) + " → " + fmt.apply(last) + ").\n";
	}

	/** Human-readable bytes (e.g. {@code 7.0 MB}) for the allocation trend line. */
	private static String humanBytes(double bytes) {
		if (bytes < 1024) {
			return String.format(Locale.ROOT, "%.0f B", bytes);
		}
		String[] units = { "KB", "MB", "GB", "TB", "PB" };
		double value = bytes / 1024.0;
		int i = 0;
		while (value >= 1024 && i < units.length - 1) {
			value /= 1024;
			i++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, units[i]);
	}

	private static String direction(double first, double last) {
		if (first <= 0 && last <= 0) {
			return "flat at ~0";
		}
		double base = (first <= 0) ? last : first;
		double change = (last - first) / base;
		if (change > TREND_BAND) {
			return "rising";
		}
		return (change < -TREND_BAND) ? "falling" : "flat";
	}

	/** Average of the first (or last) third of the run for a numeric field. */
	private static double avgThird(List<Sample> run, java.util.function.ToLongFunction<Sample> field,
			boolean firstThird) {
		int third = Math.max(1, run.size() / 3);
		int from = firstThird ? 0 : run.size() - third;
		int to = firstThird ? third : run.size();
		double sum = 0;
		for (int i = from; i < to; i++) {
			sum += field.applyAsLong(run.get(i));
		}
		return sum / (to - from);
	}

	private static String humanDuration(long ms) {
		long s = ms / 1000;
		if (s < 90) {
			return s + "s";
		}
		long m = s / 60;
		if (m < 90) {
			return m + "m";
		}
		long h = m / 60;
		return (h < 48) ? (h + "h") : ((h / 24) + "d");
	}

	private static Ranked first(List<Ranked> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}

	private static String name(Ranked r) {
		return (r != null) ? r.name() : "";
	}

	private static double share(Ranked r) {
		return (r != null) ? r.share() : 0.0;
	}

	private static long count(Ranked r) {
		return (r != null) ? r.count() : 0L;
	}

	private static String str(String v) {
		if (v == null) {
			return "\"\"";
		}
		StringBuilder b = new StringBuilder("\"");
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> b.append(c);
			}
		}
		return b.append('"').toString();
	}

	/**
	 * One interval's compact metrics across the dimensions: CPU ({@code exec},
	 * {@code hot*}), memory ({@code allocBytes}, {@code gc*}, {@code oldObjects}), wait
	 * ({@code lock}, {@code lockMs}), external I/O ({@code ioMs}), and virtual-thread
	 * pinning ({@code pinnedMs}). Component names are the JSONL keys.
	 *
	 * @param t epoch milliseconds the snapshot was taken
	 * @param exec execution samples in the window
	 * @param hot top application hot-path method (empty if none)
	 * @param hotShare its sample share (0..1)
	 * @param hotCount its absolute sample count
	 * @param gcPauses GC pause phases in the window
	 * @param gcMs total GC pause milliseconds in the window
	 * @param allocBytes estimated bytes at the top allocation site
	 * @param alloc top allocation site method (empty if none)
	 * @param oldObjects retained (old-object) samples
	 * @param lock top contended application method (empty if none)
	 * @param lockMs its blocked time in milliseconds
	 * @param cause the window's one-line heuristic cause
	 * @param ioMs total external (network + file) blocked time in the window,
	 * milliseconds
	 * @param pinnedMs total virtual-thread pinned time in the window, milliseconds
	 * @param dbMs total instrumented JDBC time in the window, milliseconds
	 * @param webMs total instrumented HTTP-endpoint time in the window, milliseconds
	 */
	public record Sample(long t, long exec, String hot, double hotShare, long hotCount, long gcPauses, long gcMs,
			long allocBytes, String alloc, long oldObjects, String lock, long lockMs, String cause, long ioMs,
			long pinnedMs, long dbMs, long webMs) {
	}

}
