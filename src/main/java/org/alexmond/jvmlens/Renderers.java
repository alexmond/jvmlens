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

	private Renderers() {
	}

	/** The compact markdown report — the human/agent-readable default. */
	static String markdown(ProfileSummary s) {
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
		mdSection(md, "Top hot paths (application code, by sample share)", s.hotPaths());
		mdSection(md, "Hot leaf methods (self-time, incl. runtime)", s.hotLeaves());
		mdSection(md, "Top allocation sites (application code, by est. bytes)", s.allocSites());
		mdSection(md, "Top allocated types (by est. bytes)", s.allocatedTypes());
		mdSection(md, "Lock contention (blocked time, by application method)", s.locks());
		if (!s.monitors().isEmpty()) {
			mdSection(md, "Contended monitors", s.monitors());
		}
		md.append("## Suspected cause (heuristic)\n- ").append(s.cause()).append('\n');
		return md.toString();
	}

	private static void mdSection(StringBuilder md, String title, List<Ranked> rows) {
		md.append("## ").append(title).append('\n');
		if (rows.isEmpty()) {
			md.append("- (none)\n\n");
			return;
		}
		for (Ranked r : rows) {
			md.append(String.format(Locale.ROOT, "- `%s` — %.0f%%", r.name(), 100.0 * r.share()));
			if (r.stack() != null) {
				md.append("  (").append(r.stack()).append(')');
			}
			md.append('\n');
		}
		md.append('\n');
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
		j.append("  \"cause\": ").append(jsonString(s.cause())).append("\n}\n");
		return j.toString();
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

	/** The markdown wrapped in a task instruction, ready to hand to an LLM. */
	static String prompt(ProfileSummary s) {
		return "You are a JVM performance expert. Below is a compact profile summary of a Java\n"
				+ "application, distilled from a JFR recording. Identify the single most likely root\n"
				+ "cause and the specific code change that would fix it. Be concise and cite the named\n"
				+ "methods; if the signal is ambiguous, say what additional evidence you would collect.\n\n" + "---\n\n"
				+ markdown(s);
	}

}
