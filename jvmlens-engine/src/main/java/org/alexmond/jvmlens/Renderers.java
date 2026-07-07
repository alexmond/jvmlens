package org.alexmond.jvmlens;

import java.util.ArrayList;
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
			appendAllocAdequacy(md, s.allocSamples(), !s.allocSites().isEmpty());
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
		if (report == Summarizer.Report.FULL) {
			appendCorrelation(md, s);
		}
		md.append("## Suspected cause (heuristic)\n- ").append(s.cause()).append('\n');
		return md.toString();
	}

	/**
	 * A hedged cross-dimension note: co-locate the dominant signal from each present
	 * dimension (endpoint / query / I/O / hot path / lock / GC) so an LLM sees the
	 * candidate chain in one place. Co-occurrence, not proof — jvmlens has no per-request
	 * trace linkage, so it suggests, it does not assert.
	 */
	private static void appendCorrelation(StringBuilder md, ProfileSummary s) {
		// Fixed causal narrative order (request → query → cache → messaging → I/O →
		// hot path → lock → GC), each link shown only if present, carrying its P1
		// source anchor and a compact flag so the reader sees *where* to look.
		List<String> links = new ArrayList<>();
		addLink(links, "endpoint", topRow(s, "web"));
		addLink(links, "SQL", topRow(s, "db"));
		addLink(links, "cache", topRow(s, "cache"));
		addLink(links, "messaging", topRow(s, "messaging"));
		addLink(links, "blocking I/O", topRow(s, "io"));
		addLink(links, "hot path", s.hotPaths().isEmpty() ? null : s.hotPaths().get(0));
		addLink(links, "lock", s.locks().isEmpty() ? null : s.locks().get(0));
		if (s.gcPauseMillis() > 200) {
			links.add("GC `" + s.gcPauseMillis() + " ms`");
		}
		if (links.size() < 2) {
			return;
		}
		md.append("## Cross-dimension correlation (heuristic)\n");
		if (startupDominated(s)) {
			// The dominant signals are bootstrap/classload/DDL, not workload — asserting
			// a
			// request→query→GC chain here is the most common misread (field-finding #70).
			md.append("- ⚠ Capture appears **startup/classload-dominated** (bootstrap frames, "
					+ "JAR/classpath I/O, or schema DDL among the top signals) — sample under "
					+ "steady-state load for workload signal.\n"
					+ "- Co-occurring (not necessarily a workload chain): ")
				.append(String.join(", ", links))
				.append(".\n\n");
			return;
		}
		md.append("- Co-occurring signals (in call order — verify they share a request path):\n");
		for (String link : links) {
			md.append("  - ").append(link).append('\n');
		}
		List<String> confirmed = confirmedChains(s);
		if (!confirmed.isEmpty()) {
			md.append("- ✓ **Confirmed chain** (shared call path — the deeper op provably ran inside the "
					+ "endpoint's call tree): ")
				.append(String.join("; ", confirmed))
				.append(".\n- Any remaining links above are co-occurrence; confirm with a focused capture.\n\n");
			return;
		}
		md.append("- Likely chain: endpoint → query → cache/alloc → GC; the anchors above are where "
				+ "to look. Co-occurrence, not proof — confirm with a focused capture.\n\n");
	}

	/**
	 * P2b linkage: a deeper op (SQL / cache / messaging) whose captured call-path passes
	 * through the endpoint's handler class ({@code ↳ under <Class>} matches the
	 * endpoint's {@code @ <Class>:line} anchor) provably ran inside that request — a
	 * confirmed chain, not co-occurrence. Empty when nothing is provable (e.g. an offline
	 * JFR with no agent-captured paths), so the caller falls back to the honest
	 * co-occurrence wording.
	 */
	private static List<String> confirmedChains(ProfileSummary s) {
		List<String> chains = new ArrayList<>();
		ProfileSummary.Ranked web = topRow(s, "web");
		String handler = (web != null) ? classOf(anchorOf(web.stack())) : null;
		if (handler == null) {
			return chains;
		}
		for (String key : List.of("db", "cache", "messaging")) {
			ProfileSummary.Ranked deep = topRow(s, key);
			if (deep != null && handler.equals(entryOf(deep.stack()))) {
				chains.add("endpoint `" + web.name() + "` → " + key + " `" + deep.name() + "` (via `" + handler + "`)");
			}
		}
		return chains;
	}

	/**
	 * The class part of a short {@code Type:line} anchor ({@code UserController}), or
	 * null.
	 */
	private static String classOf(String anchor) {
		if (anchor == null) {
			return null;
		}
		int colon = anchor.indexOf(':');
		return (colon < 0) ? anchor : anchor.substring(0, colon);
	}

	/** The entry class of a P2b {@code ↳ under <Class>} marker, or null when absent. */
	private static String entryOf(String teaser) {
		if (teaser == null) {
			return null;
		}
		int at = teaser.indexOf("↳ under ");
		if (at < 0) {
			return null;
		}
		int start = at + "↳ under ".length();
		int end = start;
		while (end < teaser.length() && !Character.isWhitespace(teaser.charAt(end))) {
			end++;
		}
		return teaser.substring(start, end);
	}

	/**
	 * Whether the dominant signals look like JVM startup / class-loading / schema setup
	 * rather than the workload — the top hot path is a bootstrap/test-harness frame, or
	 * the top external I/O is a JAR / Maven-repo read, or the top SQL is DDL. On such
	 * captures the correlation chain is misleading; soften it and tell the reader to
	 * profile under steady state (field-finding #70).
	 */
	private static boolean startupDominated(ProfileSummary s) {
		String hot = s.hotPaths().isEmpty() ? "" : s.hotPaths().get(0).name();
		boolean bootHot = hot.startsWith("org.junit.") || hot.startsWith("org.springframework.boot.")
				|| hot.contains("SpringApplication") || hot.startsWith("org.gradle.")
				|| hot.startsWith("worker.org.gradle.") || hot.startsWith("org.testng.");
		String io = nz(topName(s, "io")).toLowerCase(Locale.ROOT);
		boolean jarIo = io.contains(".jar") || io.contains("/.m2/") || io.contains("/repository/");
		String sql = nz(topName(s, "db")).strip().toLowerCase(Locale.ROOT);
		boolean ddlSql = sql.startsWith("create ") || sql.startsWith("alter ") || sql.startsWith("drop ")
				|| sql.contains("flyway_schema_history");
		return bootHot || jarIo || ddlSql;
	}

	private static String nz(String s) {
		return (s != null) ? s : "";
	}

	/** Render one chain link: {@code label `name` @ anchor (flag)}; skips a null row. */
	private static void addLink(List<String> links, String label, ProfileSummary.Ranked row) {
		if (row == null) {
			return;
		}
		StringBuilder b = new StringBuilder(label).append(" `").append(row.name()).append('`');
		String anchor = anchorOf(row.stack());
		if (anchor != null) {
			b.append(" @ ").append(anchor);
		}
		String flag = flagOf(row.stack());
		if (flag != null) {
			b.append(" (").append(flag).append(')');
		}
		links.add(b.toString());
	}

	/**
	 * The short {@code Type:line} form of a P1 {@code · at <fqn>:<line>} anchor, or null.
	 */
	private static String anchorOf(String teaser) {
		if (teaser == null) {
			return null;
		}
		int at = teaser.indexOf("· at ");
		if (at < 0) {
			return null;
		}
		int start = at + "· at ".length();
		int end = start;
		while (end < teaser.length() && !Character.isWhitespace(teaser.charAt(end)) && teaser.charAt(end) != ',') {
			end++;
		}
		String fqn = teaser.substring(start, end); // com.acme.OrderRepo:88
		int colon = fqn.indexOf(':');
		String type = (colon < 0) ? fqn : fqn.substring(0, colon);
		int dot = type.lastIndexOf('.');
		String shortType = (dot < 0) ? type : type.substring(dot + 1);
		return (colon < 0) ? shortType : (shortType + fqn.substring(colon));
	}

	/** A compact form of the row's hedged flag (N+1 / low hit rate / …), or null. */
	private static String flagOf(String teaser) {
		if (teaser == null) {
			return null;
		}
		if (teaser.contains("possible N+1")) {
			return "N+1";
		}
		if (teaser.contains("low hit rate")) {
			return "low hit rate";
		}
		if (teaser.contains("high error rate")) {
			return "high error rate";
		}
		if (teaser.contains("synchronous per-message send")) {
			return "sync send";
		}
		return null;
	}

	private static String topName(ProfileSummary s, String key) {
		ProfileSummary.Ranked row = topRow(s, key);
		return (row != null) ? row.name() : null;
	}

	private static ProfileSummary.Ranked topRow(ProfileSummary s, String key) {
		return s.sections()
			.stream()
			.filter((sec) -> sec.key().equals(key) && !sec.rows().isEmpty())
			.map((sec) -> sec.rows().get(0))
			.findFirst()
			.orElse(null);
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

	/**
	 * A caveat when there are too few allocation samples to trust the per-site byte
	 * splits — the absolute total stays reliable, the per-site shares (and their diff
	 * deltas) do not (field-finding #50 item 3). Silent when there's no allocation
	 * activity at all.
	 */
	private static void appendAllocAdequacy(StringBuilder md, long allocSamples, boolean hasSites) {
		if (hasSites && allocSamples > 0 && allocSamples < LOW_SAMPLE_THRESHOLD) {
			md.append("> ⚠ Only ")
				.append(allocSamples)
				.append(" allocation samples — the total bytes are reliable, but per-site\n"
						+ "> byte shares (and before→after per-site deltas) are statistically noisy.\n\n");
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
			.append(", \"allocSamples\": ")
			.append(s.allocSamples())
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
