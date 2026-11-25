package org.alexmond.jvmlens.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.Result;

import org.alexmond.jvmlens.ProfileSummary;
import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * The measured A/B verdicts behind {@link JvmlensProfiler}'s {@code baseline=}: JMH's
 * exact throughput (#112) and bytes/op (#104, #110, #192), each with a significance call,
 * and the {@code <jfr>.bop} sidecar that {@code keep=} writes so the next run has the
 * baseline's numbers. Pure apart from reading the sidecar.
 */
final class MeasuredVerdict {

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

	/**
	 * Below this many bytes/op a side counts as not allocating: a relative Δ between two
	 * such values is noise (0.002 → 0.0024 B/op read as +20%), and a relative Δ from one
	 * is meaningless (#192).
	 */
	private static final double ALLOC_FLOOR_BYTES = 1.0;

	private MeasuredVerdict() {
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
	static String measuredHead(Path baseline, int measuredForks, Result<?> norm, BenchmarkResult result,
			ProfileSummary before, ProfileSummary after, double sampledPct) {
		Measured base = readMeasured(baseline);
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
		return throughputHead(result, base, measuredForks, before, after)
				+ allocHead(norm, result, base, measuredForks, sampledPct);
	}

	/**
	 * The measured throughput A/B verdict (#112 finding 1) — the CPU analog of the
	 * allocation verdict. Empty when the baseline has no recorded throughput.
	 */
	private static String throughputHead(BenchmarkResult result, Measured base, int measuredForks,
			ProfileSummary before, ProfileSummary after) {
		Result<?> primary = result.getPrimaryResult();
		if (primary == null || base.tput == null) {
			return "";
		}
		int forks = Math.min(measuredForks, base.forks);
		return throughputVerdict(base.tput, base.tputErr, primary.getScore(), errOrZero(primary), forks, base.tputUnit,
				topHotPathShift(before, after)) + "\n";
	}

	/**
	 * The measured allocation A/B verdict (#104), or the {@link #jmhAllocNote} hint when
	 * this run lacks {@code -prof gc}, or a re-run hint when the baseline lacks it.
	 */
	private static String allocHead(Result<?> norm, BenchmarkResult result, Measured base, int measuredForks,
			double sampledPct) {
		if (norm == null) {
			return jmhAllocNote(result);
		}
		if (base.bop == null) {
			return "> Measured allocation A/B unavailable — re-run the baseline with `-prof gc` so the next "
					+ "diff can gate on exact bytes/op (#104).\n\n";
		}
		int forks = Math.min(measuredForks, base.forks);
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
		if (bMean < ALLOC_FLOOR_BYTES || aMean < ALLOC_FLOOR_BYTES) {
			return floorVerdict(bMean, bErr, aMean, aErr, forks);
		}
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
	 * The allocation A/B verdict when a side is under {@value #ALLOC_FLOOR_BYTES} B/op
	 * (#192). Both under: no allocation to compare, so no Δ and no significance call. One
	 * under: a new (or removed) allocation, stated as an absolute Δ — a relative Δ from
	 * ~0 is meaningless — and SIGNIFICANT only if the confidence intervals don't overlap.
	 * Pure.
	 */
	private static String floorVerdict(double bMean, double bErr, double aMean, double aErr, int forks) {
		StringBuilder b = new StringBuilder("> **Measured allocation A/B [exact]**: ");
		b.append(fineBytesPerOp(bMean)).append("/op → ").append(fineBytesPerOp(aMean)).append("/op ");
		if (bMean < ALLOC_FLOOR_BYTES && aMean < ALLOC_FLOOR_BYTES) {
			b.append(String.format(Locale.ROOT,
					"(n=%d fork(s) each) → no allocation on either side (both under %.0f B/op), nothing to compare.\n",
					forks, ALLOC_FLOOR_BYTES));
			return b.toString();
		}
		boolean ciOverlap = !(aMean + aErr < bMean - bErr || aMean - aErr > bMean + bErr);
		double delta = aMean - bMean;
		b.append(String.format(Locale.ROOT,
				"(%s — Δ %s%s/op, n=%d fork(s) each; a relative Δ from ~0 is not meaningful) → **%s**.",
				(delta > 0) ? "new allocation" : "allocation removed", (delta > 0) ? "+" : "-",
				humanBytesPerOp(Math.abs(delta)), forks, ciOverlap ? "NOT significant" : "SIGNIFICANT"));
		if (forks < 2) {
			b.append(" ⚠ a single fork understates cross-fork variance — re-run with `-f 2`+ for a real band.");
		}
		return b.append('\n').toString();
	}

	/** {@link #humanBytesPerOp}, but a sub-byte value keeps its decimals (#192). */
	private static String fineBytesPerOp(double bytes) {
		return (bytes < ALLOC_FLOOR_BYTES) ? String.format(Locale.ROOT, "%.3f B", bytes) : humanBytesPerOp(bytes);
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
	 * missing/unreadable. The fork count is {@code mforks} (measured forks merged into
	 * the figures); a pre-#191 sidecar only has {@code forks}, the <em>configured</em>
	 * count, but was rewritten by every fork — its figures are one fork's, so it counts
	 * as n=1.
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
			return new Measured(kv.get("benchmark"), (int) num(kv.get("mforks"), 1), boxed(kv.get("bop")),
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
	static String jmhAllocNote(BenchmarkResult result) {
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
	 * The {@code key=value} sidecar line for {@code JvmlensProfiler}'s {@code keep=} /
	 * {@link #readMeasured}.
	 */
	static String sidecar(BenchmarkResult result, Result<?> norm, int forks) {
		StringBuilder b = new StringBuilder();
		String bench = result.getParams().getBenchmark();
		if (bench != null) {
			b.append("benchmark=").append(bench).append(' ');
		}
		b.append("mforks=").append(forks);
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
