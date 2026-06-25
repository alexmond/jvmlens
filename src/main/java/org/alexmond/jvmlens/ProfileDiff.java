package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

/**
 * Compares two {@link ProfileSummary} snapshots (a <em>baseline</em> and an
 * <em>after</em>) into an LLM-ready <strong>delta</strong> — the core of the agent
 * optimize→measure loop: "did the fix work, and what changed." Rather than make a coding
 * agent eyeball two summaries, this names what moved: {@code GoFmt.floatString 50%→8%}, a
 * NEW hot path, GC pressure down. Per the gotmpl4j JMH field-finding (#39, gap 1).
 */
public final class ProfileDiff {

	/** Per section, how many rows (by absolute change) the delta keeps. */
	private static final int TOP_N = 8;

	/** Share changes smaller than this (percentage points) are noise, not signal. */
	private static final double MIN_DELTA_PP = 1.0;

	private ProfileDiff() {
	}

	/**
	 * Render the change from {@code before} to {@code after} as a markdown delta report.
	 * @param before the baseline summary
	 * @param after the new summary
	 * @return LLM-ready markdown
	 */
	public static String diff(ProfileSummary before, ProfileSummary after) {
		StringBuilder md = new StringBuilder("# JVM profile diff (").append(before.source())
			.append(" → ")
			.append(after.source())
			.append(")\n\n## Totals\n");
		scalar(md, "Exec samples", before.execSamples(), after.execSamples());
		scalar(md, "GC pause (ms)", before.gcPauseMillis(), after.gcPauseMillis());
		scalar(md, "Old-object samples", before.oldObjects(), after.oldObjects());
		md.append('\n');
		section(md, "Hot paths (sample share)", before.hotPaths(), after.hotPaths());
		section(md, "Allocation sites (share)", before.allocSites(), after.allocSites());
		section(md, "Lock contention (share)", before.locks(), after.locks());
		for (String key : sectionKeys(before, after)) {
			section(md, key + " (share)", sectionRows(before, key), sectionRows(after, key));
		}
		return md.toString();
	}

	private static void scalar(StringBuilder md, String label, long before, long after) {
		long delta = after - before;
		String pct = (before > 0) ? String.format(Locale.ROOT, ", %+.0f%%", 100.0 * delta / before) : "";
		md.append("- ")
			.append(label)
			.append(": ")
			.append(before)
			.append(" → ")
			.append(after)
			.append(" (")
			.append(String.format(Locale.ROOT, "%+d", delta))
			.append(pct)
			.append(")\n");
	}

	private static void section(StringBuilder md, String title, List<Ranked> before, List<Ranked> after) {
		Map<String, Double> b = shares(before);
		Map<String, Double> a = shares(after);
		Set<String> names = new LinkedHashSet<>(b.keySet());
		names.addAll(a.keySet());
		List<String[]> lines = new ArrayList<>();
		for (String name : names) {
			lines.add(line(name, b.get(name), a.get(name)));
		}
		lines.removeIf((row) -> row == null);
		lines.sort((x, y) -> Double.compare(Double.parseDouble(y[0]), Double.parseDouble(x[0])));
		md.append("## ").append(title).append('\n');
		if (lines.isEmpty()) {
			md.append("- (no significant change)\n\n");
			return;
		}
		lines.stream().limit(TOP_N).forEach((row) -> md.append(row[1]).append('\n'));
		md.append('\n');
	}

	/**
	 * One delta row as {@code [sortKey, markdown]}, or {@code null} when the change is
	 * negligible. {@code sortKey} is the absolute share change in percentage points.
	 */
	private static String[] line(String name, Double before, Double after) {
		double aPct = (after != null) ? after * 100 : 0;
		if (before == null) {
			return new String[] { String.valueOf(aPct), String.format(Locale.ROOT, "- `%s` — NEW %.0f%%", name, aPct) };
		}
		double bPct = before * 100;
		if (after == null) {
			return new String[] { String.valueOf(bPct),
					String.format(Locale.ROOT, "- `%s` — GONE (was %.0f%%)", name, bPct) };
		}
		double deltaPp = aPct - bPct;
		if (Math.abs(deltaPp) < MIN_DELTA_PP) {
			return null;
		}
		String arrow = (deltaPp > 0) ? "▲" : "▼";
		return new String[] { String.valueOf(Math.abs(deltaPp)), String.format(Locale.ROOT,
				"- `%s` — %.0f%%→%.0f%% (%s %.0fpp)", name, bPct, aPct, arrow, Math.abs(deltaPp)) };
	}

	private static Map<String, Double> shares(List<Ranked> rows) {
		Map<String, Double> m = new LinkedHashMap<>();
		rows.forEach((r) -> m.merge(r.name(), r.share(), Double::sum));
		return m;
	}

	private static Set<String> sectionKeys(ProfileSummary before, ProfileSummary after) {
		Set<String> keys = new LinkedHashSet<>();
		before.sections().forEach((s) -> keys.add(s.key()));
		after.sections().forEach((s) -> keys.add(s.key()));
		return keys;
	}

	private static List<Ranked> sectionRows(ProfileSummary s, String key) {
		return s.sections()
			.stream()
			.filter((sec) -> sec.key().equals(key))
			.findFirst()
			.map(Section::rows)
			.orElse(List.of());
	}

}
