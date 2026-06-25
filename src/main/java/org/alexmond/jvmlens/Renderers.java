package org.alexmond.jvmlens;

import java.util.List;
import java.util.Locale;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * Turns a {@link ProfileSummary} into one of the supported output formats. JSON is
 * hand-rolled rather than pulling in a mapper, so the analysis core stays dependency-free
 * (only {@code jdk.jfr.consumer}) and reusable by a future MCP front-end.
 */
final class Renderers {

	/** Below this many execution samples, hot-path shares are too noisy to trust. */
	private static final int LOW_SAMPLE_THRESHOLD = 200;

	private static final String HOT_PATHS = "Top hot paths (application code, by sample share)";

	private static final String HOT_LEAVES = "Hot leaf methods (self-time, incl. runtime)";

	private static final String ALLOC_SITES = "Top allocation sites (application code, by est. bytes)";

	private static final String ALLOC_TYPES = "Top allocated types (by est. bytes)";

	private static final String LOCKS = "Lock contention (blocked time, by application method)";

	private Renderers() {
	}

	/** The compact markdown report (full) — the human/agent-readable default. */
	static String markdown(ProfileSummary s) {
		return report(s, Summarizer.Report.FULL);
	}

	/** A concern-focused markdown report: full, or just CPU / memory / locks / GC. */
	static String report(ProfileSummary s, Summarizer.Report report) {
		StringBuilder md = new StringBuilder(baseHeader(s));
		boolean cpu = report == Summarizer.Report.FULL || report == Summarizer.Report.CPU;
		boolean mem = report == Summarizer.Report.FULL || report == Summarizer.Report.MEMORY
				|| report == Summarizer.Report.GC;
		boolean locks = report == Summarizer.Report.FULL || report == Summarizer.Report.LOCKS;
		if (cpu) {
			appendAdequacy(md, s.execSamples());
			mdSection(md, HOT_PATHS, s.hotPaths(), "samples", false);
			mdSection(md, HOT_LEAVES, s.hotLeaves(), "samples", false);
		}
		if (mem) {
			mdSection(md, ALLOC_SITES, s.allocSites(), "bytes", false);
			mdSection(md, ALLOC_TYPES, s.allocatedTypes(), "bytes", false);
		}
		if (locks) {
			mdSection(md, LOCKS, s.locks(), "ms", true);
			if (!s.monitors().isEmpty()) {
				mdSection(md, "Contended monitors", s.monitors(), "ms", true);
			}
		}
		for (ProfileSummary.Section sec : s.sections()) {
			if (report == Summarizer.Report.FULL || report.name().equalsIgnoreCase(sec.key())) {
				mdSection(md, sec.title(), sec.rows(), sec.unit(), sec.measured());
			}
		}
		md.append("## Suspected cause (heuristic)\n- ").append(s.cause()).append('\n');
		return md.toString();
	}

	private static String baseHeader(ProfileSummary s) {
		StringBuilder md = new StringBuilder();
		md.append("# JVM profile summary (")
			.append(s.source())
			.append(")\n\nEvents: ")
			.append(s.execSamples())
			.append(" exec samples, ")
			.append(s.allocTypes())
			.append(" alloc types, ")
			.append(s.oldObjects())
			.append(" old-object samples, ")
			.append(s.gcPauses())
			.append(" GC pauses (")
			.append(s.gcPauseMillis())
			.append(" ms).\n\n");
		if (s.appPackage() != null) {
			md.append("Application code under `").append(s.appPackage()).append(".*`.\n\n");
		}
		return md.toString();
	}

	/**
	 * A caveat line when there are too few (or no) execution samples to attribute CPU.
	 */
	private static void appendAdequacy(StringBuilder md, long execSamples) {
		if (execSamples == 0) {
			md.append("> ⚠ No execution samples — CPU attribution unavailable; the recording may be too\n"
					+ "> short or CPU sampling disabled.\n\n");
		}
		else if (execSamples < LOW_SAMPLE_THRESHOLD) {
			md.append("> ⚠ Only ")
				.append(execSamples)
				.append(" execution samples — hot-path shares are statistically noisy;\n"
						+ "> capture longer or under steady-state load (warm JVM, `profile --warmup`).\n\n");
		}
	}

	/** One ranked section rendered on its own — the unit the MCP tools hand back. */
	static String section(String title, List<Ranked> rows, String countUnit, boolean measured) {
		StringBuilder md = new StringBuilder();
		mdSection(md, title, rows, countUnit, measured);
		return md.toString();
	}

	private static void mdSection(StringBuilder md, String title, List<Ranked> rows, String countUnit,
			boolean measured) {
		md.append("## ").append(title).append(measured ? " [measured]\n" : " [sampled]\n");
		if (rows.isEmpty()) {
			md.append("- (none)\n\n");
			return;
		}
		for (Ranked r : rows) {
			md.append(String.format(Locale.ROOT, "- `%s` — %.0f%%", r.name(), 100.0 * r.share()));
			if (countUnit != null) {
				md.append(" (").append(formatCount(r.count(), countUnit)).append(')');
			}
			if (r.stack() != null) {
				md.append("  (").append(r.stack()).append(')');
			}
			md.append('\n');
		}
		md.append('\n');
	}

	/**
	 * Render a row's absolute weight: human-readable bytes, ms from nanos, else raw
	 * count.
	 */
	private static String formatCount(long count, String unit) {
		return switch (unit) {
			case "bytes" -> humanBytes(count);
			case "ms" -> (count / 1_000_000) + " ms";
			default -> count + " " + unit;
		};
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

	/** A scoped JSON object carrying the same ranked signal as the markdown. */
	static String json(ProfileSummary s) {
		StringBuilder j = new StringBuilder();
		j.append("{\n  \"source\": ").append(jsonString(s.source()));
		j.append(",\n  \"events\": {\"execSamples\": ")
			.append(s.execSamples())
			.append(", \"allocTypes\": ")
			.append(s.allocTypes())
			.append(", \"oldObjects\": ")
			.append(s.oldObjects())
			.append(", \"gcPauses\": ")
			.append(s.gcPauses())
			.append(", \"gcPauseMillis\": ")
			.append(s.gcPauseMillis())
			.append("},\n");
		jsonArray(j, "hotPaths", s.hotPaths());
		jsonArray(j, "hotLeaves", s.hotLeaves());
		jsonArray(j, "allocSites", s.allocSites());
		jsonArray(j, "allocatedTypes", s.allocatedTypes());
		jsonArray(j, "locks", s.locks());
		jsonArray(j, "monitors", s.monitors());
		jsonSections(j, s.sections());
		j.append("  \"appPackage\": ").append(jsonString(s.appPackage()));
		j.append(",\n  \"cause\": ").append(jsonString(s.cause())).append("\n}\n");
		return j.toString();
	}

	private static void jsonSections(StringBuilder j, List<ProfileSummary.Section> sections) {
		j.append("  \"sections\": [");
		for (int i = 0; i < sections.size(); i++) {
			ProfileSummary.Section sec = sections.get(i);
			j.append((i == 0) ? "\n" : ",\n");
			j.append("    {\"key\": ")
				.append(jsonString(sec.key()))
				.append(", \"title\": ")
				.append(jsonString(sec.title()))
				.append(", \"measured\": ")
				.append(sec.measured())
				.append(", \"rows\": [");
			for (int r = 0; r < sec.rows().size(); r++) {
				Ranked row = sec.rows().get(r);
				j.append((r == 0) ? "" : ", ");
				j.append("{\"name\": ")
					.append(jsonString(row.name()))
					.append(", \"share\": ")
					.append(String.format(Locale.ROOT, "%.4f", row.share()))
					.append(", \"count\": ")
					.append(row.count())
					.append('}');
			}
			j.append("]}");
		}
		j.append(sections.isEmpty() ? "],\n" : "\n  ],\n");
	}

	private static void jsonArray(StringBuilder j, String name, List<Ranked> rows) {
		j.append("  \"").append(name).append("\": [");
		for (int i = 0; i < rows.size(); i++) {
			Ranked r = rows.get(i);
			j.append((i == 0) ? "\n" : ",\n");
			j.append("    {\"name\": ")
				.append(jsonString(r.name()))
				.append(", \"share\": ")
				.append(String.format(Locale.ROOT, "%.4f", r.share()))
				.append(", \"count\": ")
				.append(r.count())
				.append(", \"stack\": ")
				.append(jsonString(r.stack()))
				.append('}');
		}
		j.append(rows.isEmpty() ? "],\n" : "\n  ],\n");
	}

	private static String jsonString(String v) {
		if (v == null) {
			return "null";
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
				default -> {
					if (c < 0x20) {
						b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
					}
					else {
						b.append(c);
					}
				}
			}
		}
		return b.append('"').toString();
	}

	/** The full markdown wrapped in a task instruction, ready to hand to an LLM. */
	static String prompt(ProfileSummary s) {
		return promptOf(markdown(s));
	}

	/** Wrap an already-rendered report body in the LLM task instruction. */
	static String promptOf(String body) {
		return "You are a JVM performance expert. Below is a compact profile summary of a Java\n"
				+ "application, distilled from a JFR recording. Identify the single most likely root\n"
				+ "cause and the specific code change that would fix it. Be concise and cite the named\n"
				+ "methods; if the signal is ambiguous, say what additional evidence you would collect.\n\n" + "---\n\n"
				+ body;
	}

}
