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

	/**
	 * Below this many allocation samples (either side), per-site byte deltas are noise.
	 */
	private static final long LOW_ALLOC_SAMPLES = 200;

	/**
	 * A sampled allocation-total delta smaller than this (single recording) may be noise
	 * (#104).
	 */
	private static final double SAMPLED_NOISE_PCT = 15.0;

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
		sampledAllocNoiseNote(md, before.allocBytes(), after.allocBytes());
		md.append('\n');
		section(md, "Hot paths", "samples", before.hotPaths(), after.hotPaths(),
				redistributionNote(before.execSamples(), after.execSamples(), "samples"));
		section(md, "Allocation sites", "bytes", before.allocSites(), after.allocSites(),
				redistributionNote(before.allocBytes(), after.allocBytes(), "alloc"));
		lowAllocSampleNote(md, before.allocSamples(), after.allocSamples(),
				before.allocBytes() > 0 || after.allocBytes() > 0);
		allocTypeRollup(md, before.allocSites(), after.allocSites());
		section(md, "Lock contention", "ms", before.locks(), after.locks(), null);
		for (String key : sectionKeys(before, after)) {
			section(md, key, unit(before, after, key), sectionRows(before, key), sectionRows(after, key), null);
		}
		return md.toString();
	}

	/**
	 * A section-level caveat when either recording has too few allocation samples for the
	 * per-site byte deltas to be trustworthy — the absolute total stays reliable, the
	 * per-site splits don't (field-finding #50 item 3). Emitted only when there was
	 * allocation activity at all.
	 */
	private static void lowAllocSampleNote(StringBuilder md, long before, long after, boolean hasAlloc) {
		boolean noisy = (before > 0 && before < LOW_ALLOC_SAMPLES) || (after > 0 && after < LOW_ALLOC_SAMPLES);
		if (hasAlloc && noisy) {
			md.append("> ⚠ Low allocation samples (before ")
				.append(before)
				.append(" / after ")
				.append(after)
				.append(") — per-site byte deltas are statistically noisy; trust the absolute total.\n\n");
		}
	}

	/**
	 * A hedge note for a row whose absolute change <em>opposes</em> the section's total
	 * change — almost certainly JFR <em>sampling</em> redistribution, not a real per-row
	 * regression. For allocation (#52): when the dominant allocator shrinks, the sampler
	 * reattributes weight toward whatever's next, so a flat site's <em>attributed</em>
	 * bytes balloon. For CPU hot paths (#110 finding 2): when total execution samples
	 * move, a row's share/absolute can rise purely because the rest of the profile shrank
	 * — so a ▲ row need not mean the method got slower. Returns {@code null} when the
	 * total didn't meaningfully move (no redistribution context to give). Annotates only
	 * — never suppresses or re-ranks the row, since a genuine localized regression on a
	 * shifting run looks the same and the absolute anchor (#43/#44) must stay legible.
	 * @param noun the total's unit for the message (e.g. {@code "alloc"},
	 * {@code "samples"})
	 */
	private static RowNote redistributionNote(long totalBefore, long totalAfter, String noun) {
		long totalDelta = totalAfter - totalBefore;
		if (totalBefore <= 0 || Math.abs((double) totalDelta) / totalBefore < MIN_REL_CHANGE) {
			return null;
		}
		String dir = (totalDelta < 0) ? "fell" : "rose";
		long pct = Math.round(100.0 * Math.abs(totalDelta) / totalBefore);
		return (delta) -> (delta != 0 && (delta > 0) != (totalDelta > 0))
				? "  (possible sampling redistribution — total " + noun + " " + dir + " " + pct + "%)" : "";
	}

	/**
	 * A caveat when the allocation total moved, but by less than
	 * {@value #SAMPLED_NOISE_PCT}% — sampled JFR allocation from a single recording is
	 * noisy at that scale, so a modest delta can be sampling noise rather than a real
	 * reduction. Confirm with exact bytes/op (#104).
	 */
	private static void sampledAllocNoiseNote(StringBuilder md, long before, long after) {
		if (before <= 0 || after == before) {
			return;
		}
		double pct = Math.abs(100.0 * (after - before) / before);
		if (pct >= MIN_REL_CHANGE * 100 && pct < SAMPLED_NOISE_PCT) {
			md.append("> ⚠ The allocation total is **sampled** — a sub-")
				.append((int) SAMPLED_NOISE_PCT)
				.append("% delta from a single recording can be within sampling noise; confirm with JMH "
						+ "`-prof gc` (exact bytes/op) or diff multi-fork recordings.\n");
		}
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

	private static void section(StringBuilder md, String title, String unit, List<Ranked> before, List<Ranked> after,
			RowNote note) {
		Map<String, Long> bc = counts(before);
		Map<String, Long> ac = counts(after);
		Map<String, Double> bs = shares(before);
		Map<String, Double> as = shares(after);
		Set<String> names = new LinkedHashSet<>(bc.keySet());
		names.addAll(ac.keySet());
		List<String[]> lines = new ArrayList<>();
		for (String name : names) {
			lines.add(line(name, bc.get(name), ac.get(name), share(bs, name), share(as, name), unit, note));
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
	private static String[] line(String name, Long before, Long after, double bShare, double aShare, String unit,
			RowNote note) {
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
		String extra = (note != null) ? note.note(delta) : "";
		return new String[] { String.valueOf(Math.abs(delta)),
				String.format(Locale.ROOT, "- `%s` — %s → %s (%s %.0f%%) [share %.0f%%→%.0f%%]%s", name,
						formatVal(before, unit), formatVal(after, unit), arrow, Math.abs(pct), bShare * 100,
						aShare * 100, extra) };
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

	/**
	 * Roll allocation sites up to their declaring type and show the net before→after per
	 * type, so an extract-method refactor (caller ▼ + a {@code NEW} callee in the same
	 * class) reads as one number instead of looking like a regression. Only types with ≥2
	 * contributing methods and a non-negligible net change appear (field-finding #99).
	 */
	private static void allocTypeRollup(StringBuilder md, List<Ranked> before, List<Ranked> after) {
		Map<String, Long> bc = counts(before);
		Map<String, Long> ac = counts(after);
		Map<String, Set<String>> methodsByType = new LinkedHashMap<>();
		for (String m : bc.keySet()) {
			methodsByType.computeIfAbsent(declaringType(m), (k) -> new LinkedHashSet<>()).add(m);
		}
		for (String m : ac.keySet()) {
			methodsByType.computeIfAbsent(declaringType(m), (k) -> new LinkedHashSet<>()).add(m);
		}
		Map<String, Long> bt = sumByType(bc);
		Map<String, Long> at = sumByType(ac);
		List<String[]> rows = new ArrayList<>();
		for (Map.Entry<String, Set<String>> e : methodsByType.entrySet()) {
			if (e.getValue().size() < 2) {
				continue; // a single-method type: the per-site row already tells the
							// story
			}
			long b = bt.getOrDefault(e.getKey(), 0L);
			long a = at.getOrDefault(e.getKey(), 0L);
			long delta = a - b;
			if (b > 0 && Math.abs((double) delta) / b < MIN_REL_CHANGE) {
				continue; // net barely moved
			}
			String arrow = (delta > 0) ? "▲" : "▼";
			double pct = (b > 0) ? (100.0 * delta / b) : 0;
			rows.add(new String[] { String.valueOf(Math.abs(delta)),
					String.format(Locale.ROOT, "- `%s.*` — %s → %s (%s %.0f%%) [%d methods]", e.getKey(),
							formatVal(b, "bytes"), formatVal(a, "bytes"), arrow, Math.abs(pct), e.getValue().size()) });
		}
		if (rows.isEmpty()) {
			return;
		}
		rows.sort((x, y) -> Long.compare(Long.parseLong(y[0]), Long.parseLong(x[0])));
		md.append("## Allocation by type (rollup — extracted helpers summed)\n");
		rows.stream().limit(TOP_N).forEach((r) -> md.append(r[1]).append('\n'));
		md.append('\n');
	}

	private static Map<String, Long> sumByType(Map<String, Long> byMethod) {
		Map<String, Long> m = new LinkedHashMap<>();
		byMethod.forEach((name, bytes) -> m.merge(declaringType(name), bytes, Long::sum));
		return m;
	}

	/**
	 * The declaring type of a {@code Type.method} name (everything before the last dot).
	 */
	private static String declaringType(String method) {
		int dot = method.lastIndexOf('.');
		return (dot > 0) ? method.substring(0, dot) : method;
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

	/**
	 * An optional per-row hedge note keyed on the row's absolute change ({@code ""} =
	 * none).
	 */
	@FunctionalInterface
	private interface RowNote {

		String note(long delta);

	}

}
