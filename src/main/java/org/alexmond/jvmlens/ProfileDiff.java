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
 * optimize→measure loop: "did the fix work, and what changed."
 *
 * <p>
 * Anchored on <strong>absolute</strong> weight (bytes / ms / samples), not share alone:
 * optimizing shrinks the denominator, so a site whose absolute bytes fell can have a
 * <em>rising</em> share — share-only diffing inverts exactly where this feature is used
 * (field-finding #43). Each row shows the absolute before→after with its direction, and
 * the share change as secondary context.
 */
public final class ProfileDiff {

	/** Per section, how many rows (by absolute change) the delta keeps. */
	private static final int TOP_N = 8;

	/** Relative change below this (vs the baseline value) is noise, not signal. */
	private static final double MIN_REL_CHANGE = 0.05;

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
		scalar(md, "Exec samples", before.execSamples(), after.execSamples(), "");
		scalar(md, "Allocation", before.allocBytes(), after.allocBytes(), "bytes");
		scalar(md, "GC pause", before.gcPauseMillis(), after.gcPauseMillis(), "ms-direct");
		scalar(md, "Old-object samples", before.oldObjects(), after.oldObjects(), "");
		md.append('\n');
		section(md, "Hot paths", "samples", before.hotPaths(), after.hotPaths());
		section(md, "Allocation sites", "bytes", before.allocSites(), after.allocSites());
		section(md, "Lock contention", "ms", before.locks(), after.locks());
		for (String key : sectionKeys(before, after)) {
			section(md, key, unit(before, after, key), sectionRows(before, key), sectionRows(after, key));
		}
		return md.toString();
	}

	private static void scalar(StringBuilder md, String label, long before, long after, String unit) {
		long delta = after - before;
		String pct = (before > 0) ? String.format(Locale.ROOT, ", %+.0f%%", 100.0 * delta / before) : "";
		md.append("- ")
			.append(label)
			.append(": ")
			.append(formatVal(before, unit))
			.append(" → ")
			.append(formatVal(after, unit))
			.append(" (")
			.append((unit.isEmpty()) ? String.format(Locale.ROOT, "%+d", delta) : signed(delta, unit))
			.append(pct)
			.append(")\n");
	}

	private static void section(StringBuilder md, String title, String unit, List<Ranked> before, List<Ranked> after) {
		Map<String, Long> bc = counts(before);
		Map<String, Long> ac = counts(after);
		Map<String, Double> bs = shares(before);
		Map<String, Double> as = shares(after);
		Set<String> names = new LinkedHashSet<>(bc.keySet());
		names.addAll(ac.keySet());
		List<String[]> lines = new ArrayList<>();
		for (String name : names) {
			lines.add(line(name, bc.get(name), ac.get(name), share(bs, name), share(as, name), unit));
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
	 * One delta row as {@code [sortKey, markdown]}, or {@code null} when negligible.
	 * {@code sortKey} is the absolute change in the row's native unit.
	 */
	private static String[] line(String name, Long before, Long after, double bShare, double aShare, String unit) {
		if (before == null) {
			return new String[] { String.valueOf(after), String.format(Locale.ROOT, "- `%s` — NEW %s (%.0f%% share)",
					name, formatVal(after, unit), aShare * 100) };
		}
		if (after == null) {
			return new String[] { String.valueOf(before), String.format(Locale.ROOT, "- `%s` — GONE (was %s, %.0f%%)",
					name, formatVal(before, unit), bShare * 100) };
		}
		long delta = after - before;
		if (before > 0 && Math.abs((double) delta) / before < MIN_REL_CHANGE) {
			return null;
		}
		String arrow = (delta > 0) ? "▲" : "▼";
		double pct = (before > 0) ? (100.0 * delta / before) : 0;
		return new String[] { String.valueOf(Math.abs(delta)),
				String.format(Locale.ROOT, "- `%s` — %s → %s (%s %.0f%%) [share %.0f%%→%.0f%%]", name,
						formatVal(before, unit), formatVal(after, unit), arrow, Math.abs(pct), bShare * 100,
						aShare * 100) };
	}

	/**
	 * Render a weight in its native unit ({@code bytes} → human, {@code ms} → from
	 * nanos).
	 */
	private static String formatVal(long count, String unit) {
		return switch (unit) {
			case "bytes" -> humanBytes(count);
			case "ms" -> (count / 1_000_000) + " ms";
			case "ms-direct" -> count + " ms";
			default -> String.valueOf(count);
		};
	}

	private static String signed(long delta, String unit) {
		return ((delta >= 0) ? "+" : "-") + formatVal(Math.abs(delta), unit);
	}

	private static String humanBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
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

	private static Map<String, Long> counts(List<Ranked> rows) {
		Map<String, Long> m = new LinkedHashMap<>();
		rows.forEach((r) -> m.merge(r.name(), r.count(), Long::sum));
		return m;
	}

	private static Map<String, Double> shares(List<Ranked> rows) {
		Map<String, Double> m = new LinkedHashMap<>();
		rows.forEach((r) -> m.merge(r.name(), r.share(), Double::sum));
		return m;
	}

	private static double share(Map<String, Double> m, String name) {
		return m.getOrDefault(name, 0.0);
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

	private static String unit(ProfileSummary before, ProfileSummary after, String key) {
		return before.sections()
			.stream()
			.filter((sec) -> sec.key().equals(key))
			.map(Section::unit)
			.findFirst()
			.or(() -> after.sections().stream().filter((sec) -> sec.key().equals(key)).map(Section::unit).findFirst())
			.orElse("");
	}

}
