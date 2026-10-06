package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * A CI performance gate over a {@link ProfileDiff}: evaluate a comma/semicolon-separated
 * list of {@code metric < threshold} rules against a baseline→after pair and fail
 * (non-zero exit) on regression. Headless, backend-free, and PR-scoped — exactly the
 * ground the SaaS APMs can't take (they own prod, not the pull request). Per the gotmpl4j
 * field-finding (#39, gap 3).
 *
 * <p>
 * Metrics: {@code gc-ms} (after GC pause ms ceiling), {@code gc-pct} (GC pause increase
 * %), {@code alloc-pct} (<em>total</em> allocation-byte increase % — the absolute memory
 * gate, immune to the share-shuffle of field-finding #43), {@code oldobj-delta}
 * (retained-sample growth), {@code regression-pp} (largest hot-path share increase, pp),
 * {@code new-hotpath-pp} (largest NEW hot-path share). All use {@code <} ("must stay
 * under"). Prefer the absolute gates ({@code gc-*}, {@code alloc-pct},
 * {@code oldobj-delta}). The {@code *-pp} ones are share-based, but a share move only
 * counts as far as the absolute sample count backs it (see {@code confirmed}), and NEW
 * means absent from the whole baseline — feed summaries built under
 * {@link RankLimits#full} so that is the full distribution, not a top-N (#165).
 */
public final class PerfGate {

	private PerfGate() {
	}

	/**
	 * Evaluate {@code spec} against the before→after change.
	 * @param before the baseline summary
	 * @param after the new summary
	 * @param spec comma/semicolon-separated {@code metric < threshold} rules
	 * @return the gate result (passed + a markdown report)
	 */
	public static Result evaluate(ProfileSummary before, ProfileSummary after, String spec) {
		List<String> lines = new ArrayList<>();
		boolean ok = true;
		for (String raw : spec.split("[,;]")) {
			String rule = raw.trim();
			if (rule.isEmpty()) {
				continue;
			}
			int lt = rule.indexOf('<');
			Double threshold = (lt > 0) ? parse(rule.substring(lt + 1)) : null;
			if (threshold == null) {
				lines.add("- ⚠ unrecognized rule (expected `metric < number`): " + rule);
				ok = false;
				continue;
			}
			Eval e = check(rule.substring(0, lt).trim(), threshold, before, after);
			ok = ok && e.passed;
			lines.add((e.passed ? "- ✅ " : "- ❌ ") + rule + " — " + e.detail);
		}
		return new Result(ok, "## Perf gate" + (ok ? " — PASS\n" : " — FAIL\n") + String.join("\n", lines) + "\n");
	}

	private static Eval check(String metric, double threshold, ProfileSummary before, ProfileSummary after) {
		return switch (metric) {
			case "gc-ms" -> num(after.gcPauseMillis(), threshold, "after GC = " + after.gcPauseMillis() + " ms");
			case "gc-pct" -> pct("GC ms", before.gcPauseMillis(), after.gcPauseMillis(), threshold);
			case "alloc-pct" -> pct("alloc bytes", before.allocBytes(), after.allocBytes(), threshold);
			case "oldobj-delta" -> num(after.oldObjects() - before.oldObjects(), threshold,
					"old-objects " + before.oldObjects() + " → " + after.oldObjects());
			case "regression-pp" -> regression(before, after, threshold);
			case "new-hotpath-pp" -> newHot(before, after, threshold);
			default -> new Eval(false, "unknown metric `" + metric + "`");
		};
	}

	private static Eval num(double actual, double threshold, String detail) {
		return new Eval(actual < threshold, detail + " (limit " + fmt(threshold) + ")");
	}

	private static Eval pct(String label, long before, long after, double threshold) {
		double pct = (before > 0) ? (100.0 * (after - before) / before) : ((after > 0) ? Double.POSITIVE_INFINITY : 0);
		String shown = Double.isInfinite(pct) ? "∞" : String.format(Locale.ROOT, "%+.0f", pct);
		return new Eval(pct < threshold,
				label + " " + before + " → " + after + " (" + shown + "%, limit " + fmt(threshold) + "%)");
	}

	private static Eval regression(ProfileSummary before, ProfileSummary after, double threshold) {
		Map<String, Ranked> b = byName(before.hotPaths());
		String worst = null;
		double worstPp = 0;
		for (Ranked r : after.hotPaths()) {
			Ranked prev = b.get(r.name());
			if (prev != null) {
				double pp = confirmed((r.share() - prev.share()) * 100, r.count() - prev.count(), before.execSamples());
				if (pp > worstPp) {
					worstPp = pp;
					worst = r.name();
				}
			}
		}
		String detail = (worst != null) ? ("`" + worst + "` +" + fmt(worstPp) + "pp") : "no shared hot path";
		return new Eval(worstPp < threshold, detail + " (limit " + fmt(threshold) + "pp)");
	}

	private static Eval newHot(ProfileSummary before, ProfileSummary after, double threshold) {
		Map<String, Ranked> b = byName(before.hotPaths());
		Ranked worst = null;
		double worstPp = 0;
		for (Ranked r : after.hotPaths()) {
			double pp = confirmed(r.share() * 100, r.count(), before.execSamples());
			if (!b.containsKey(r.name()) && pp > worstPp) {
				worstPp = pp;
				worst = r;
			}
		}
		String detail = (worst != null)
				? ("NEW `" + worst.name() + "` " + fmt(worst.share() * 100) + "% share, "
						+ fmt(ofBaseline(worst.count(), before.execSamples())) + "% of the baseline total")
				: "no new hot path";
		return new Eval(worstPp < threshold, detail + " (limit " + fmt(threshold) + "%)");
	}

	/**
	 * A share move counts only as far as the <em>absolute</em> samples back it: the
	 * smaller of the share change and the sample change as a % of the baseline total.
	 * Optimizing shrinks the denominator, so a path whose samples fell (or a small new
	 * path) can show a large share of a much smaller total — share alone failed every
	 * improvement-only diff (#165, the gate-side twin of #43). Falls back to share when
	 * the summaries carry no sample counts.
	 */
	private static double confirmed(double sharePp, long sampleDelta, long baselineTotal) {
		return (baselineTotal > 0) ? Math.min(sharePp, ofBaseline(sampleDelta, baselineTotal)) : sharePp;
	}

	private static double ofBaseline(long samples, long baselineTotal) {
		return (baselineTotal > 0) ? (100.0 * samples / baselineTotal) : 0;
	}

	private static Map<String, Ranked> byName(List<Ranked> rows) {
		Map<String, Ranked> m = new LinkedHashMap<>();
		rows.forEach((r) -> m.putIfAbsent(r.name(), r));
		return m;
	}

	private static Double parse(String s) {
		try {
			return Double.valueOf(s.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String fmt(double d) {
		return String.format(Locale.ROOT, "%.0f", d);
	}

	/** The gate outcome: whether every rule passed, and a markdown report of each. */
	public record Result(boolean passed, String report) {
	}

	private record Eval(boolean passed, String detail) {
	}

}
