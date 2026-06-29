package org.alexmond.jvmlens.jmh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.ExternalProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.Result;

import org.alexmond.jvmlens.ProfileDiff;
import org.alexmond.jvmlens.ProfileSummary;
import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.Scope;
import org.alexmond.jvmlens.Summarizer;

/**
 * A JMH {@link ExternalProfiler} that records the benchmark fork with JFR and prints the
 * jvmlens summary inline when the trial ends — so {@code java -jar benchmarks.jar -prof
 * org.alexmond.jvmlens.jmh.JvmlensProfiler} gives you the ranked hot paths / allocation
 * sites next to JMH's number, no separate {@code analyze} step.
 *
 * <p>
 * Options (after a colon, {@code key=value} separated by {@code ;}): {@code appPackage}
 * (application-frame scope; {@code +} or {@code ,}-separate several — {@code appPackages}
 * is accepted as an alias so the scope syntax matches the CLI's {@code -a}),
 * {@code report} (full/cpu/memory/locks/gc/...), {@code keep} (write the fork's recording
 * to this path instead of deleting it — so it can be the next run's baseline), and
 * {@code baseline} (a prior recording to diff this run against — prints the change report
 * instead of the plain summary, so {@code run → "what changed vs last run"} happens in
 * one JMH command; field-finding #50 item 2), and {@code socketio} (default {@code false}
 * — drops socket I/O events, which on a JMH fork are just the harness control socket; set
 * {@code socketio=true} to keep them; field-finding #100). An <em>unknown</em> option key
 * is a hard error (a {@link ProfilerException} with a did-you-mean suggestion) rather
 * than a silent no-op — a misspelled {@code appPackage} that produced an unscoped summary
 * used to cost a whole capture before you noticed (field-finding #53 item 6).
 *
 * <p>
 * When the run also enables JMH's GC profiler ({@code -prof gc}), the summary is headed
 * with JMH's <em>exact</em> {@code gc.alloc.rate.norm} (bytes/op) so the measured rate
 * sits next to jvmlens's <em>sampled</em> per-site bytes (field-finding #100). With a
 * {@code baseline=}, that becomes a measured A/B verdict (#104) plus a
 * <em>dispersion</em> verdict (#110): a real structural allocation removal collapses the
 * cross-fork variance band, and a now near-deterministic bytes/op is the loop's STOP
 * signal. A measured <em>throughput</em> A/B verdict (#112) sits alongside — the CPU
 * analog — flagging a sampled hot-path share that moved while wall-clock throughput
 * stayed flat (a CPU-share shift is not a speedup); and the {@code baseline=} comparison
 * is benchmark-matched, so a baseline recorded for a different method warns and skips
 * rather than emitting nonsense. Ships in the dependency-light {@code jvmlens-jmh.jar}
 * (engine + this profiler, no Spring/picocli/jmh) — put it and {@code jmh-core} on the
 * benchmark's classpath.
 */
public class JvmlensProfiler implements ExternalProfiler {

	/** Recognized option keys, used for the did-you-mean suggestion on a typo. */
	private static final List<String> KNOWN_KEYS = List.of("appPackage", "report", "keep", "baseline", "socketio");

	/**
	 * The cross-fork error band must shrink by at least this factor to call it a variance
	 * collapse (#110 finding 1).
	 */
	private static final double VARIANCE_COLLAPSE_FACTOR = 3.0;

	/**
	 * Below this relative error band (err/mean), the measured bytes/op is treated as
	 * near-deterministic — the STOP signal for the optimize loop (#110 finding 1).
	 */
	private static final double DETERMINISTIC_REL_BAND = 0.005;

	/**
	 * A hot-path share that shifted by at least this (percentage points) is worth naming
	 * in the flat-throughput caveat (#112 finding 1).
	 */
	private static final double HOT_SHIFT_PP = 0.10;

	private final Path jfr;

	private List<String> appPackages = List.of();

	private Summarizer.Report report = Summarizer.Report.FULL;

	private Path keep;

	private Path baseline;

	/**
	 * Keep socket I/O events? Default false — drops the JMH harness control socket
	 * (#100).
	 */
	private boolean socketIo;

	/** No-arg form: {@code -prof org.alexmond.jvmlens.jmh.JvmlensProfiler}. */
	public JvmlensProfiler() throws IOException {
		this.jfr = Files.createTempFile("jvmlens-jmh", ".jfr");
	}

	/**
	 * Options form: {@code -prof "...JvmlensProfiler:appPackage=com.acme;report=cpu"}.
	 * @param initLine the JMH option string, or {@code null}
	 * @throws IOException if the temp recording file cannot be created
	 * @throws ProfilerException if an option key is unknown or a pair is malformed
	 */
	public JvmlensProfiler(String initLine) throws IOException, ProfilerException {
		this();
		parse(initLine);
	}

	private void parse(String initLine) throws ProfilerException {
		if (initLine == null) {
			return;
		}
		for (String pair : initLine.split(";")) {
			String trimmed = pair.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			int eq = trimmed.indexOf('=');
			if (eq <= 0) {
				throw new ProfilerException("jvmlens: malformed option `" + trimmed + "` — expected key=value");
			}
			applyOption(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
		}
	}

	private void applyOption(String key, String value) throws ProfilerException {
		switch (key) {
			case "appPackage", "appPackages" -> {
				this.appPackages = List.of(value.split("[+,]"));
			}
			case "report" -> applyReport(value);
			case "keep" -> {
				this.keep = Path.of(value);
			}
			case "baseline" -> {
				this.baseline = Path.of(value);
			}
			case "socketio" -> {
				this.socketIo = "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
			}
			default -> throw new ProfilerException(unknownOption(key));
		}
	}

	private static String unknownOption(String key) {
		String nearest = KNOWN_KEYS.stream().min(Comparator.comparingInt((k) -> distance(key, k))).orElse("appPackage");
		return "jvmlens: unknown profiler option `" + key + "` — did you mean `" + nearest + "`? (valid: "
				+ String.join(", ", KNOWN_KEYS) + ")";
	}

	private static int distance(String a, String b) {
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			for (int j = 1; j <= b.length(); j++) {
				int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
			}
		}
		return d[a.length()][b.length()];
	}

	private void applyReport(String value) {
		try {
			this.report = Summarizer.Report.valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			System.out.println("jvmlens: unknown report focus `" + value + "` — using full");
		}
	}

	@Override
	public Collection<String> addJVMInvokeOptions(BenchmarkParams params) {
		return Collections.emptyList();
	}

	@Override
	public Collection<String> addJVMOptions(BenchmarkParams params) {
		String rec = "-XX:StartFlightRecording=filename=" + this.jfr + ",settings=profile,dumponexit=true";
		if (!this.socketIo) {
			// On a JMH fork the only socket traffic is the harness control socket (a
			// rotating
			// localhost:<ephemeral> port) — noise that skews External I/O + the
			// cross-dimension
			// correlation (field-finding #100). Drop it at the source; `socketio=true`
			// keeps it.
			rec += ",jdk.SocketRead#enabled=false,jdk.SocketWrite#enabled=false";
		}
		return List.of(rec);
	}

	@Override
	public void beforeTrial(BenchmarkParams params) {
		// nothing to do before the fork starts — JFR is armed via addJVMOptions
	}

	@Override
	public Collection<? extends Result> afterTrial(BenchmarkResult result, long pid, File stdOut, File stdErr) {
		Result<?> norm = result.getSecondaryResults().get("gc.alloc.rate.norm");
		try {
			Scope scope = Scope.of(this.appPackages, List.of());
			if (this.baseline != null) {
				ProfileSummary before = Summarizer.analyze(this.baseline, scope);
				ProfileSummary after = Summarizer.analyze(this.jfr, scope);
				double sampledPct = (before.allocBytes() > 0)
						? 100.0 * (after.allocBytes() - before.allocBytes()) / before.allocBytes() : 0;
				System.out.println(
						"\n" + measuredHead(norm, result, before, after, sampledPct) + ProfileDiff.diff(before, after));
			}
			else {
				System.out.println("\n" + jmhAllocNote(result)
						+ Summarizer.summarize(this.jfr, Summarizer.Format.MARKDOWN, scope, this.report));
			}
		}
		catch (IOException ex) {
			System.out.println("jvmlens: could not summarize the benchmark recording: " + ex.getMessage());
		}
		finally {
			retainRecording(result, norm);
		}
		return Collections.<Result>emptyList();
	}

	/**
	 * The header for a {@code baseline=} run: a measured <em>throughput</em> A/B verdict
	 * (#112) and a measured <em>allocation</em> A/B verdict (#104), each gated on JMH's
	 * exact numbers and reconciled against the sampled diff below. Guards the
	 * multi-method footgun (#112 finding 2): if the kept baseline was recorded for a
	 * <em>different</em> benchmark method than this trial, the per-method comparison
	 * would be nonsense, so it warns and skips the measured verdicts rather than emitting
	 * confident wrong numbers.
	 */
	private String measuredHead(Result<?> norm, BenchmarkResult result, ProfileSummary before, ProfileSummary after,
			double sampledPct) {
		Measured base = readMeasured(this.baseline);
		if (base == null) {
			return "> Measured A/B unavailable — re-run the baseline with `keep=` (and `-prof gc`) so the "
					+ "next diff can gate on exact numbers (#104/#112). Sampled diff below.\n\n";
		}
		String current = result.getParams().getBenchmark();
		if (base.benchmark != null && current != null && !base.benchmark.equals(current)) {
			return String.format(Locale.ROOT,
					"> ⚠ Baseline mismatch — the kept baseline is for `%s`, this trial is `%s`. The inline "
							+ "`baseline=` matches a single benchmark method; record a per-benchmark baseline. "
							+ "Skipping the measured A/B to avoid confident wrong numbers (#112). Sampled diff "
							+ "below.\n\n",
					simpleName(base.benchmark), simpleName(current));
		}
		return throughputHead(result, base, before, after) + allocHead(norm, result, base, sampledPct);
	}

	/**
	 * The measured throughput A/B verdict (#112 finding 1) — the CPU analog of the
	 * allocation verdict. Empty when the baseline has no recorded throughput.
	 */
	private String throughputHead(BenchmarkResult result, Measured base, ProfileSummary before, ProfileSummary after) {
		Result<?> primary = result.getPrimaryResult();
		if (primary == null || base.tput == null) {
			return "";
		}
		int forks = Math.min(result.getParams().getForks(), base.forks);
		return throughputVerdict(base.tput, base.tputErr, primary.getScore(), errOrZero(primary), forks, base.tputUnit,
				topHotPathShift(before, after)) + "\n";
	}

	/**
	 * The measured allocation A/B verdict (#104), or the {@link #jmhAllocNote} hint when
	 * this run lacks {@code -prof gc}, or a re-run hint when the baseline lacks it.
	 */
	private String allocHead(Result<?> norm, BenchmarkResult result, Measured base, double sampledPct) {
		if (norm == null) {
			return jmhAllocNote(result);
		}
		if (base.bop == null) {
			return "> Measured allocation A/B unavailable — re-run the baseline with `-prof gc` so the next "
					+ "diff can gate on exact bytes/op (#104).\n\n";
		}
		int forks = Math.min(result.getParams().getForks(), base.forks);
		return allocVerdict(base.bop, base.bopErr, norm.getScore(), errOrZero(norm), forks, sampledPct) + "\n";
	}

	/**
	 * A measured throughput A/B verdict with the same significance discipline as
	 * {@link #allocVerdict}: SIGNIFICANT only if the relative Δ exceeds the combined
	 * noise band <em>and</em> the confidence intervals don't overlap. When throughput is
	 * flat but a sampled hot-path share moved, it says so — a CPU-share shift is not a
	 * speedup (#112). Phrasing is direction-neutral (Δ%), since higher-is-better vs
	 * lower-is-better depends on the JMH mode. Pure.
	 */
	static String throughputVerdict(double bMean, double bErr, double aMean, double aErr, int forks, String unit,
			String hotShift) {
		double pct = (bMean != 0) ? 100.0 * (aMean - bMean) / bMean : 0;
		double band = (bMean != 0 && aMean != 0) ? 100.0 * (bErr / bMean + aErr / aMean) : 0;
		boolean ciOverlap = !(aMean + aErr < bMean - bErr || aMean - aErr > bMean + bErr);
		boolean significant = Math.abs(pct) > band && !ciOverlap;
		String u = (unit == null || unit.isBlank()) ? "" : " " + unit;
		StringBuilder b = new StringBuilder("> **Measured throughput A/B [exact]**: ");
		b.append(humanScore(bMean)).append(u).append(" → ").append(humanScore(aMean)).append(u);
		b.append(String.format(Locale.ROOT, " (Δ %+.1f%%, n=%d fork(s) each, noise band ±%.1f%%) → **%s**.", pct, forks,
				band, significant ? "SIGNIFICANT" : "NOT significant"));
		if (forks < 2) {
			b.append(" ⚠ a single fork understates cross-fork variance — re-run with `-f 2`+ for a real band.");
		}
		if (!significant && hotShift != null && !hotShift.isEmpty()) {
			b.append(" ⚠ the sampled hot-path share moved (")
				.append(hotShift)
				.append(") but throughput is flat — a CPU-share shift is not a speedup; the win (if any) is in "
						+ "allocation/GC.");
		}
		return b.append('\n').toString();
	}

	/**
	 * The hot path whose <em>share</em> moved most between the two runs, as
	 * {@code method b%→a%} (or {@code ""} when none shifted by {@value #HOT_SHIFT_PP}
	 * ·100 pp) — names the row a flat-throughput caveat is about.
	 */
	private static String topHotPathShift(ProfileSummary before, ProfileSummary after) {
		Map<String, Double> bs = sharesByName(before.hotPaths());
		Map<String, Double> as = sharesByName(after.hotPaths());
		Set<String> names = new LinkedHashSet<>(bs.keySet());
		names.addAll(as.keySet());
		String best = null;
		double bestDelta = 0;
		double bShare = 0;
		double aShare = 0;
		for (String name : names) {
			double sb = bs.getOrDefault(name, 0.0);
			double sa = as.getOrDefault(name, 0.0);
			if (Math.abs(sa - sb) > bestDelta) {
				bestDelta = Math.abs(sa - sb);
				best = name;
				bShare = sb;
				aShare = sa;
			}
		}
		if (best == null || bestDelta < HOT_SHIFT_PP) {
			return "";
		}
		return simpleName(best) + String.format(Locale.ROOT, " %.0f%%→%.0f%%", bShare * 100, aShare * 100);
	}

	private static Map<String, Double> sharesByName(List<Ranked> rows) {
		Map<String, Double> m = new LinkedHashMap<>();
		rows.forEach((r) -> m.merge(r.name(), r.share(), Double::sum));
		return m;
	}

	/** The simple {@code Type.method} tail of a fully-qualified name, for legibility. */
	private static String simpleName(String fqn) {
		String[] parts = fqn.split("\\.");
		return (parts.length >= 2) ? parts[parts.length - 2] + "." + parts[parts.length - 1] : fqn;
	}

	/** Humanize a JMH primary score (throughput) with a few significant figures. */
	private static String humanScore(double score) {
		if (score == 0.0) {
			return "0";
		}
		double abs = Math.abs(score);
		if (abs >= 1000 || abs < 0.001) {
			return String.format(Locale.ROOT, "%.4g", score);
		}
		return String.format(Locale.ROOT, "%.4f", score);
	}

	/**
	 * A measured allocation A/B verdict with a significance call: the change is
	 * SIGNIFICANT only if the relative Δ exceeds the combined noise band <em>and</em> the
	 * confidence intervals don't overlap — otherwise the sampled diff may be a phantom
	 * (JIT elision / sampling redistribution). Reconciles the sampled total Δ against the
	 * measured Δ (#104). Pure.
	 */
	static String allocVerdict(double bMean, double bErr, double aMean, double aErr, int forks, double sampledPct) {
		double measuredPct = (bMean != 0) ? 100.0 * (aMean - bMean) / bMean : 0;
		double band = (bMean != 0 && aMean != 0) ? 100.0 * (bErr / bMean + aErr / aMean) : 0;
		boolean ciOverlap = !(aMean + aErr < bMean - bErr || aMean - aErr > bMean + bErr);
		boolean significant = Math.abs(measuredPct) > band && !ciOverlap;
		StringBuilder b = new StringBuilder("> **Measured allocation A/B [exact]**: ");
		b.append(humanBytesPerOp(bMean)).append("/op → ").append(humanBytesPerOp(aMean)).append("/op ");
		b.append(String.format(Locale.ROOT, "(Δ %+.1f%%, n=%d fork(s) each, noise band ±%.1f%%) → **%s**.", measuredPct,
				forks, band, significant ? "SIGNIFICANT" : "NOT significant"));
		if (forks < 2) {
			b.append(" ⚠ a single fork understates cross-fork variance — re-run with `-f 2`+ for a real band.");
		}
		boolean disagree = (!significant && Math.abs(sampledPct) >= 5.0)
				|| (sampledPct * measuredPct < 0 && Math.abs(sampledPct) >= 5.0 && Math.abs(measuredPct) >= 5.0);
		if (disagree) {
			b.append(String.format(Locale.ROOT,
					" The **sampled** total Δ (%+.1f%%) disagrees — likely sampling "
							+ "redistribution / JIT elision, not a real change; trust the measured number.",
					sampledPct));
		}
		b.append('\n').append(dispersionNote(bMean, bErr, aMean, aErr, measuredPct));
		return b.toString();
	}

	/**
	 * A dispersion verdict over the cross-fork error bands (#110 finding 1): a genuine
	 * structural allocation removal doesn't just lower the mean — it eliminates the
	 * GC-sampling variance that <em>was</em> that allocation, so the band collapses (the
	 * round-3 win went ±17,200 → ±35, ~500×, while the round-2 phantom left ±18K → ±18K).
	 * Surfaces two things the mean alone can't: a <strong>variance collapse</strong>
	 * alongside a mean drop is a strong real-removal signal, and a now
	 * <strong>near-deterministic</strong> bytes/op is the loop's STOP signal (further
	 * allocation tuning has diminishing returns; the residual is intrinsic floor). Empty
	 * when there is no meaningful before-band to compare (e.g. a single fork → no error
	 * term). Pure.
	 */
	static String dispersionNote(double bMean, double bErr, double aMean, double aErr, double measuredPct) {
		if (bMean <= 0 || aMean <= 0 || bErr <= 0) {
			return "";
		}
		double collapse = (aErr > 0) ? bErr / aErr : Double.POSITIVE_INFINITY;
		double afterRel = aErr / aMean;
		boolean collapsed = collapse >= VARIANCE_COLLAPSE_FACTOR && measuredPct <= -5.0;
		boolean deterministic = afterRel <= DETERMINISTIC_REL_BAND;
		if (!collapsed && !deterministic) {
			return "";
		}
		StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "> Dispersion: ±%s/op → ±%s/op",
				humanBytesPerOp(bErr), humanBytesPerOp(aErr)));
		if (collapsed) {
			String factor = Double.isInfinite(collapse) ? "to ~0" : String.format(Locale.ROOT, "~%.0f×", collapse);
			b.append(" — **variance collapsed ")
				.append(factor)
				.append("** alongside the mean drop → strong "
						+ "real structural-removal signal (the removed allocation was the dominant noise source).");
		}
		if (deterministic) {
			b.append(collapsed ? " " : " — ")
				.append(String.format(Locale.ROOT,
						"allocation is now **near-deterministic** (±%.2f%%/op) → diminishing returns; the residual "
								+ "is intrinsic floor — pivot off allocation.",
						afterRel * 100));
		}
		return b.append('\n').toString();
	}

	private static double errOrZero(Result<?> norm) {
		double err = norm.getScoreError();
		return Double.isNaN(err) ? 0.0 : err;
	}

	/**
	 * Read a kept recording's {@code <jfr>.bop} sidecar (space-separated
	 * {@code key=value} pairs, written by {@link #sidecar}), or {@code null} if
	 * missing/unreadable.
	 */
	static Measured readMeasured(Path jfr) {
		try {
			Map<String, String> kv = new LinkedHashMap<>();
			for (String tok : Files.readString(Path.of(jfr + ".bop")).trim().split("\\s+")) {
				int eq = tok.indexOf('=');
				if (eq > 0) {
					kv.put(tok.substring(0, eq), tok.substring(eq + 1));
				}
			}
			return new Measured(kv.get("benchmark"), (int) num(kv.get("forks"), 1), boxed(kv.get("bop")),
					num(kv.get("boperr"), 0), boxed(kv.get("tput")), num(kv.get("tputerr"), 0), kv.get("tputunit"));
		}
		catch (IOException | RuntimeException ignored) {
			return null; // missing/unreadable sidecar — fall back to the no-baseline note
		}
	}

	private static double num(String s, double fallback) {
		try {
			double v = Double.parseDouble(s);
			return Double.isNaN(v) ? fallback : v;
		}
		catch (NumberFormatException | NullPointerException ignored) {
			return fallback;
		}
	}

	private static Double boxed(String s) {
		try {
			double v = Double.parseDouble(s);
			return Double.isNaN(v) ? null : v;
		}
		catch (NumberFormatException | NullPointerException ignored) {
			return null;
		}
	}

	/**
	 * A one-line note pairing JMH's <em>exact</em> measured allocation
	 * ({@code gc.alloc.rate.norm}, bytes/op) with jvmlens's <em>sampled</em> totals
	 * below, so the two sit together and any divergence is visible rather than hidden
	 * (field-finding #100). Only present when the run also enabled JMH's GC profiler
	 * ({@code -prof gc}); otherwise a hint to add it.
	 */
	private static String jmhAllocNote(BenchmarkResult result) {
		Result<?> norm = result.getSecondaryResults().get("gc.alloc.rate.norm");
		if (norm == null) {
			return "> jvmlens allocation figures below are **[sampled]** estimates — add `-prof gc` "
					+ "for JMH's exact bytes/op next to them.\n\n";
		}
		String exact = humanBytesPerOp(norm.getScore());
		return "> JMH measured allocation **[exact]**: " + exact + "/op (gc.alloc.rate.norm). "
				+ "jvmlens's per-site bytes below are **[sampled]** — a gap vs this exact rate is "
				+ "expected; trust this number for the rate, jvmlens for *where*.\n\n";
	}

	/**
	 * Humanize a bytes-per-op figure (JMH reports {@code gc.alloc.rate.norm} in B/op).
	 */
	private static String humanBytesPerOp(double bytes) {
		if (bytes >= 1024.0 * 1024.0) {
			return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
		}
		if (bytes >= 1024.0) {
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		}
		return String.format(Locale.ROOT, "%.0f B", bytes);
	}

	/**
	 * Keep the fork's recording at {@code keep} (for the next run's baseline), else
	 * delete it. When kept, also persist a {@code <keep>.bop} sidecar (the benchmark,
	 * fork count, measured throughput, and exact bytes/op when {@code -prof gc} was on)
	 * so the next {@code baseline=} run can render the measured throughput (#112) +
	 * allocation (#104) A/B verdicts and detect a benchmark mismatch.
	 */
	private void retainRecording(BenchmarkResult result, Result<?> norm) {
		try {
			if (this.keep != null) {
				Files.move(this.jfr, this.keep, StandardCopyOption.REPLACE_EXISTING);
				System.out.println("jvmlens: recording kept at " + this.keep);
				Files.writeString(Path.of(this.keep + ".bop"), sidecar(result, norm));
			}
			else {
				Files.deleteIfExists(this.jfr);
			}
		}
		catch (IOException ignored) {
			// best-effort retention/cleanup of the temp recording
		}
	}

	/**
	 * The {@code key=value} sidecar line for {@link #retainRecording} /
	 * {@link #readMeasured}.
	 */
	private static String sidecar(BenchmarkResult result, Result<?> norm) {
		StringBuilder b = new StringBuilder();
		String bench = result.getParams().getBenchmark();
		if (bench != null) {
			b.append("benchmark=").append(bench).append(' ');
		}
		b.append("forks=").append(result.getParams().getForks());
		Result<?> primary = result.getPrimaryResult();
		if (primary != null) {
			b.append(" tput=").append(primary.getScore()).append(" tputerr=").append(errOrZero(primary));
			String unit = primary.getScoreUnit();
			if (unit != null && !unit.isBlank()) {
				b.append(" tputunit=").append(unit);
			}
		}
		if (norm != null) {
			b.append(" bop=").append(norm.getScore()).append(" boperr=").append(errOrZero(norm));
		}
		return b.toString();
	}

	@Override
	public boolean allowPrintOut() {
		return true;
	}

	@Override
	public boolean allowPrintErr() {
		return true;
	}

	@Override
	public String getDescription() {
		return "jvmlens — LLM-ready JFR summary of the benchmark, printed when the trial ends";
	}

	/**
	 * A kept recording's measured-numbers sidecar ({@code <jfr>.bop}): the benchmark it
	 * was recorded for, fork count, exact bytes/op ({@code bop}, when {@code -prof gc}
	 * was on), and the primary throughput score, each with its error term. Any field may
	 * be absent.
	 */
	static final class Measured {

		final String benchmark;

		final int forks;

		final Double bop;

		final double bopErr;

		final Double tput;

		final double tputErr;

		final String tputUnit;

		Measured(String benchmark, int forks, Double bop, double bopErr, Double tput, double tputErr, String tputUnit) {
			this.benchmark = benchmark;
			this.forks = forks;
			this.bop = bop;
			this.bopErr = bopErr;
			this.tput = tput;
			this.tputErr = tputErr;
			this.tputUnit = tputUnit;
		}

	}

}
