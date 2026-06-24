package org.alexmond.jvmlens;

/**
 * The scoped, navigable surface over a recording — the operations the MCP server exposes
 * as separate tools so an agent pulls only the slice it needs (progressive disclosure:
 * overview → drill into hot paths → pull allocation sites) instead of one big blob. Each
 * method formats a focused, LLM-ready markdown fragment from an already-analyzed
 * {@link ProfileSummary}; the caller decides how to obtain it (and under what
 * {@link Scope}).
 */
final class ProfileTools {

	private ProfileTools() {
	}

	/** Orientation: event counts, the heuristic cause, and where to drill next. */
	static String overview(ProfileSummary s) {
		String appLine = (s.appPackage() != null) ? "Application code under `" + s.appPackage() + ".*`.\n\n" : "";
		return "# JVM profile overview (" + s.source() + ")\n\n" + "Events: " + s.execSamples() + " exec samples, "
				+ s.allocTypes() + " alloc types, " + s.oldObjects() + " old-object samples, " + s.gcPauses()
				+ " GC pauses (" + s.gcPauseMillis() + " ms).\n\n" + appLine + "Suspected cause: " + s.cause() + "\n\n"
				+ "Drill in with the hot_paths, hot_leaves, allocations, and lock_contention tools.\n";
	}

	/** Application-attributed hot call paths, by sample share. */
	static String hotPaths(ProfileSummary s) {
		return Renderers.section("Top hot paths (application code, by sample share)", s.hotPaths(), "samples", false);
	}

	/** Leaf (self-time) hot methods, runtime included. */
	static String hotLeaves(ProfileSummary s) {
		return Renderers.section("Hot leaf methods (self-time, incl. runtime)", s.hotLeaves(), "samples", false);
	}

	/** Allocation sites and allocated types, by estimated bytes. */
	static String allocations(ProfileSummary s) {
		return Renderers.section("Top allocation sites (application code, by est. bytes)", s.allocSites(), "bytes",
				false) + Renderers.section("Top allocated types (by est. bytes)", s.allocatedTypes(), "bytes", false);
	}

	/** Lock contention by application method, plus the contended monitor classes. */
	static String lockContention(ProfileSummary s) {
		return Renderers.section("Lock contention (blocked time, by application method)", s.locks(), "ms", true)
				+ Renderers.section("Contended monitors", s.monitors(), "ms", true);
	}

}
