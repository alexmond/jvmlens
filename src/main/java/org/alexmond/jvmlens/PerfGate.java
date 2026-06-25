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
 * Metrics: {@code gc-ms} (after GC pause ms, absolute ceiling), {@code gc-pct} (GC pause
 * increase %), {@code oldobj-delta} (retained-sample growth), {@code regression-pp}
 * (largest hot-path share increase, percentage points), {@code new-hotpath-pp} (largest
 * NEW hot-path share). All use {@code <} ("must stay under").
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
			case "gc-pct" -> pct(before.gcPauseMillis(), after.gcPauseMillis(), threshold);
			case "oldobj-delta" -> num(after.oldObjects() - before.oldObjects(), threshold,
					"old-objects " + before.oldObjects() + " → " + after.oldObjects());
			case "regression-pp" -> regression(before.hotPaths(), after.hotPaths(), threshold);
			case "new-hotpath-pp" -> newHot(before.hotPaths(), after.hotPaths(), threshold);
			default -> new Eval(false, "unknown metric `" + metric + "`");
		};
	}

	private static Eval num(double actual, double threshold, String detail) {
		return new Eval(actual < threshold, detail + " (limit " + fmt(threshold) + ")");
	}

	private static Eval pct(long before, long after, double threshold) {
		double pct = (before > 0) ? (100.0 * (after - before) / before) : ((after > 0) ? Double.POSITIVE_INFINITY : 0);
		String shown = Double.isInfinite(pct) ? "∞" : String.format(Locale.ROOT, "%+.0f", pct);
		return new Eval(pct < threshold,
				"GC " + before + " → " + after + " ms (" + shown + "%, limit " + fmt(threshold) + "%)");
	}

	private static Eval regression(List<Ranked> before, List<Ranked> after, double threshold) {
		Map<String, Double> b = shares(before);
		String worst = null;
		double worstPp = 0;
		for (Ranked r : after) {
			Double prev = b.get(r.name());
			if (prev != null) {
				double pp = (r.share() - prev) * 100;
				if (pp > worstPp) {
					worstPp = pp;
					worst = r.name();
				}
			}
		}
		String detail = (worst != null) ? ("`" + worst + "` +" + fmt(worstPp) + "pp") : "no shared hot path";
		return new Eval(worstPp < threshold, detail + " (limit " + fmt(threshold) + "pp)");
	}

	private static Eval newHot(List<Ranked> before, List<Ranked> after, double threshold) {
		Map<String, Double> b = shares(before);
		String worst = null;
		double worstPp = 0;
		for (Ranked r : after) {
			if (!b.containsKey(r.name()) && r.share() * 100 > worstPp) {
				worstPp = r.share() * 100;
				worst = r.name();
			}
		}
		String detail = (worst != null) ? ("NEW `" + worst + "` " + fmt(worstPp) + "%") : "no new hot path";
		return new Eval(worstPp < threshold, detail + " (limit " + fmt(threshold) + "%)");
	}

	private static Map<String, Double> shares(List<Ranked> rows) {
		Map<String, Double> m = new LinkedHashMap<>();
		rows.forEach((r) -> m.merge(r.name(), r.share(), Double::sum));
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
