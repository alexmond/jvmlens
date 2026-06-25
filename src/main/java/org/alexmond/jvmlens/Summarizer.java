package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * The core of jvmlens: read a JFR recording and reduce it to a compact, ranked,
 * source-attributed {@link ProfileSummary}. A multi-megabyte {@code jfr print} dump
 * collapses to roughly a few hundred tokens of signal.
 *
 * <p>
 * Dependency-free — uses only {@code jdk.jfr.consumer}. Rendering (markdown / JSON /
 * prompt) lives in {@link Renderers}; what counts as application code is decided by
 * {@link Scope}. Keep both free of framework deps so the planned MCP server can serve the
 * same structured result.
 */
public final class Summarizer {

	/** Output formats the CLI can request. */
	public enum Format {

		/** Compact markdown report (default). */
		MARKDOWN,
		/** Scoped JSON object with the same ranked signal. */
		JSON,
		/** Markdown wrapped in an LLM task instruction. */
		PROMPT

	}

	/** Which concern a report focuses on. */
	public enum Report {

		/** Everything. */
		FULL,
		/** Hot paths + leaf methods (sampled CPU). */
		CPU,
		/** Allocation sites + types. */
		MEMORY,
		/** Lock contention + contended monitors (measured wait). */
		LOCKS,
		/** GC pressure and the allocation that drives it. */
		GC,
		/** External (network + file) blocking I/O by endpoint. */
		IO,
		/** Virtual-thread pinning sites. */
		PINNING,
		/** Top SQL statements (agent JDBC instrumentation). */
		DB,
		/** Top HTTP endpoints (agent servlet instrumentation). */
		WEB

	}

	/** How many rows each ranked section keeps. */
	private static final int TOP_N = 5;

	private Summarizer() {
	}

	/**
	 * Summarize a JFR recording as markdown with the default scope.
	 * @param file the {@code .jfr} recording to read
	 * @return a compact markdown report
	 * @throws IOException if the recording cannot be read
	 */
	public static String summarize(Path file) throws IOException {
		return summarize(file, Format.MARKDOWN, Scope.defaults());
	}

	/**
	 * Summarize a JFR recording in the requested format with the default scope.
	 * @param file the {@code .jfr} recording to read
	 * @param format the output format
	 * @return the rendered report
	 * @throws IOException if the recording cannot be read
	 */
	public static String summarize(Path file, Format format) throws IOException {
		return summarize(file, format, Scope.defaults());
	}

	/**
	 * Summarize a JFR recording in the requested format and application scope.
	 * @param file the {@code .jfr} recording to read
	 * @param format the output format
	 * @param scope which frames count as application code
	 * @return the rendered report
	 * @throws IOException if the recording cannot be read
	 */
	public static String summarize(Path file, Format format, Scope scope) throws IOException {
		return summarize(file, format, scope, Report.FULL);
	}

	/**
	 * Summarize a JFR recording in the requested format, scope, and report focus.
	 * @param file the {@code .jfr} recording to read
	 * @param format the output format
	 * @param scope which frames count as application code
	 * @param report which concern to focus the report on
	 * @return the rendered report
	 * @throws IOException if the recording cannot be read
	 */
	public static String summarize(Path file, Format format, Scope scope, Report report) throws IOException {
		return render(analyze(file, scope), format, report);
	}

	/**
	 * Render an already-analyzed summary in the requested format (full report).
	 * @param s the structured summary
	 * @param format the output format
	 * @return the rendered report
	 */
	public static String render(ProfileSummary s, Format format) {
		return render(s, format, Report.FULL);
	}

	/**
	 * Render an already-analyzed summary in the requested format and report focus.
	 * @param s the structured summary
	 * @param format the output format
	 * @param report which concern to focus the report on
	 * @return the rendered report
	 */
	public static String render(ProfileSummary s, Format format, Report report) {
		return switch (format) {
			case MARKDOWN -> Renderers.report(s, report);
			case JSON -> Renderers.json(s);
			case PROMPT -> Renderers.promptOf(Renderers.report(s, report));
		};
	}

	/**
	 * Read a JFR recording into the render-agnostic structured summary, default scope.
	 * @param file the {@code .jfr} recording to read
	 * @return the structured summary
	 * @throws IOException if the recording cannot be read
	 */
	public static ProfileSummary analyze(Path file) throws IOException {
		return analyze(file, Scope.defaults());
	}

	/**
	 * Read a JFR recording into the render-agnostic structured summary.
	 * @param file the {@code .jfr} recording to read
	 * @param scope which frames count as application code
	 * @return the structured summary
	 * @throws IOException if the recording cannot be read
	 */
	public static ProfileSummary analyze(Path file, Scope scope) throws IOException {
		Aggregates agg = new Aggregates(scope);
		try (RecordingFile rf = new RecordingFile(file)) {
			while (rf.hasMoreEvents()) {
				agg.add(rf.readEvent());
			}
		}
		return agg.toSummary(file);
	}

	/** The leaf (top-of-stack) java frame — the self-time view, runtime included. */
	private static String leafFrame(RecordedStackTrace st) {
		return firstFrame(st, (owner) -> true);
	}

	/** The first application frame walking down from the leaf, per {@code scope}. */
	private static String appFrame(RecordedStackTrace st, Scope scope) {
		return firstFrame(st, scope::isApplication);
	}

	private static String firstFrame(RecordedStackTrace st, Predicate<String> ownerMatches) {
		if (st == null) {
			return null;
		}
		return st.getFrames()
			.stream()
			.filter((f) -> f.isJavaFrame() && f.getMethod() != null)
			.filter((f) -> ownerMatches.test(f.getMethod().getType().getName()))
			.map((f) -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
			.findFirst()
			.orElse(null);
	}

	private static String topFrames(RecordedStackTrace st, int n) {
		if (st == null) {
			return "";
		}
		List<String> out = new ArrayList<>();
		for (RecordedFrame f : st.getFrames()) {
			if (f.isJavaFrame() && f.getMethod() != null) {
				out.add(f.getMethod().getType().getName() + "." + f.getMethod().getName());
				if (out.size() >= n) {
					break;
				}
			}
		}
		return String.join(" <- ", out);
	}

	private static long sum(Map<String, Long> m) {
		return m.values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * Top-N rows of {@code m} as shares of {@code total}, newest-stack teaser attached.
	 */
	private static List<Ranked> ranked(Map<String, Long> m, long total, Map<String, String> stacks) {
		List<Ranked> rows = new ArrayList<>();
		if (total <= 0) {
			return rows;
		}
		m.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(TOP_N)
			.forEach((en) -> rows.add(new Ranked(en.getKey(), (double) en.getValue() / total, en.getValue(),
					(stacks != null) ? stacks.get(en.getKey()) : null)));
		return rows;
	}

	private static String top(Map<String, Long> m) {
		return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	/** Human-readable bytes (e.g. {@code 2.1 MB}) for I/O teasers. */
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
		return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[i]);
	}

	/** The most-sampled two-segment package among application frames, or {@code null}. */
	private static String detectAppPackage(Map<String, Long> appByWeight) {
		Map<String, Long> byPackage = new HashMap<>();
		appByWeight.forEach((method, weight) -> {
			String prefix = packagePrefix(method);
			if (prefix != null) {
				byPackage.merge(prefix, weight, Long::sum);
			}
		});
		return top(byPackage);
	}

	/**
	 * First two package segments of a {@code pkg.Class.method} name (e.g.
	 * {@code org.alexmond}).
	 */
	private static String packagePrefix(String method) {
		int beforeMethod = method.lastIndexOf('.');
		if (beforeMethod < 0) {
			return null;
		}
		String type = method.substring(0, beforeMethod);
		int firstDot = type.indexOf('.');
		if (firstDot < 0) {
			return null;
		}
		int secondDot = type.indexOf('.', firstDot + 1);
		return (secondDot < 0) ? type.substring(0, firstDot) : type.substring(0, secondDot);
	}

	/** Mutable accumulator for the events of a single recording. */
	private static final class Aggregates {

		private final Scope scope;

		private final Map<String, Long> cpuByApp = new HashMap<>();

		private final Map<String, String> appStack = new HashMap<>();

		private final Map<String, Long> cpuByLeaf = new HashMap<>();

		private final Map<String, Long> allocBySite = new HashMap<>();

		private final Map<String, Long> allocByType = new HashMap<>();

		private final Map<String, Long> lockByMethod = new HashMap<>();

		private final Map<String, Long> lockByMonitor = new HashMap<>();

		private final Map<String, Long> ioByEndpoint = new HashMap<>();

		private final Map<String, Long> ioBytes = new HashMap<>();

		private final Map<String, Long> ioOps = new HashMap<>();

		private final Map<String, Long> pinnedBySite = new HashMap<>();

		private final Map<String, String> pinnedReason = new HashMap<>();

		private long execSamples;

		private long allocBytes;

		private long oldObjects;

		private long gcPauses;

		private long gcPauseNanos;

		private Aggregates(Scope scope) {
			this.scope = scope;
		}

		private void add(RecordedEvent e) {
			switch (e.getEventType().getName()) {
				case "jdk.ExecutionSample" -> addExecution(e);
				case "jdk.ObjectAllocationSample" -> addAllocation(e);
				case "jdk.OldObjectSample" -> {
					this.oldObjects++;
				}
				case "jdk.JavaMonitorEnter" -> addLock(e);
				case "jdk.GCPhasePause" -> addGc(e);
				case "jdk.SocketRead", "jdk.SocketWrite" -> addSocketIo(e);
				case "jdk.FileRead", "jdk.FileWrite" -> addFileIo(e);
				case "jdk.VirtualThreadPinned" -> addPinned(e);
				default -> {
				}
			}
		}

		private void addSocketIo(RecordedEvent e) {
			String host = e.hasField("host") ? e.getString("host") : null;
			String addr = e.hasField("address") ? e.getString("address") : null;
			String where = (host != null && !host.isBlank()) ? host : addr;
			long port = e.hasField("port") ? e.getLong("port") : -1;
			String endpoint = (where != null) ? (where + ((port >= 0) ? (":" + port) : "")) : "unknown";
			addIo(endpoint, e, "bytesRead", "bytesWritten");
		}

		private void addFileIo(RecordedEvent e) {
			String path = e.hasField("path") ? e.getString("path") : "unknown";
			addIo("file " + path, e, "bytesRead", "bytesWritten");
		}

		private void addIo(String endpoint, RecordedEvent e, String readField, String writeField) {
			this.ioByEndpoint.merge(endpoint, e.getDuration().toNanos(), Long::sum);
			this.ioOps.merge(endpoint, 1L, Long::sum);
			long bytes = e.hasField(readField) ? e.getLong(readField)
					: (e.hasField(writeField) ? e.getLong(writeField) : 0);
			this.ioBytes.merge(endpoint, Math.max(bytes, 0), Long::sum);
		}

		private void addPinned(RecordedEvent e) {
			String site = appFrame(e.getStackTrace(), this.scope);
			if (site == null) {
				site = leafFrame(e.getStackTrace());
			}
			if (site == null) {
				site = "unknown";
			}
			this.pinnedBySite.merge(site, e.getDuration().toNanos(), Long::sum);
			if (e.hasField("pinnedReason")) {
				Object reason = e.getValue("pinnedReason");
				if (reason != null) {
					this.pinnedReason.putIfAbsent(site, reason.toString());
				}
			}
		}

		private void addExecution(RecordedEvent e) {
			this.execSamples++;
			String leaf = leafFrame(e.getStackTrace());
			if (leaf != null) {
				this.cpuByLeaf.merge(leaf, 1L, Long::sum);
			}
			String app = appFrame(e.getStackTrace(), this.scope);
			if (app != null) {
				this.cpuByApp.merge(app, 1L, Long::sum);
				this.appStack.putIfAbsent(app, topFrames(e.getStackTrace(), 3));
			}
		}

		private void addAllocation(RecordedEvent e) {
			long w = e.hasField("weight") ? e.getLong("weight") : 0;
			this.allocBytes += w;
			if (e.hasField("objectClass") && e.getClass("objectClass") != null) {
				this.allocByType.merge(e.getClass("objectClass").getName(), w, Long::sum);
			}
			String site = appFrame(e.getStackTrace(), this.scope);
			if (site != null) {
				this.allocBySite.merge(site, w, Long::sum);
			}
		}

		private void addLock(RecordedEvent e) {
			long d = e.getDuration().toNanos();
			String m = appFrame(e.getStackTrace(), this.scope);
			if (m != null) {
				this.lockByMethod.merge(m, d, Long::sum);
			}
			if (e.hasField("monitorClass") && e.getClass("monitorClass") != null) {
				this.lockByMonitor.merge(e.getClass("monitorClass").getName(), d, Long::sum);
			}
		}

		private void addGc(RecordedEvent e) {
			this.gcPauses++;
			this.gcPauseNanos += e.getDuration().toNanos();
		}

		private ProfileSummary toSummary(Path file) {
			return new ProfileSummary(file.getFileName().toString(), this.execSamples, this.allocByType.size(),
					this.oldObjects, this.gcPauses, this.gcPauseNanos / 1_000_000,
					ranked(this.cpuByApp, this.execSamples, this.appStack),
					ranked(this.cpuByLeaf, this.execSamples, null), ranked(this.allocBySite, this.allocBytes, null),
					ranked(this.allocByType, this.allocBytes, null),
					ranked(this.lockByMethod, sum(this.lockByMethod), null),
					ranked(this.lockByMonitor, sum(this.lockByMonitor), null), heuristic(),
					detectAppPackage(detectionWeights()), extendedSections());
		}

		/** The beyond-CPU/memory/wait dimensions, only those with any signal. */
		private List<ProfileSummary.Section> extendedSections() {
			List<ProfileSummary.Section> out = new ArrayList<>();
			if (!this.ioByEndpoint.isEmpty()) {
				out.add(new ProfileSummary.Section("io", "External I/O (blocked time, by endpoint)", "ms", true,
						ranked(this.ioByEndpoint, sum(this.ioByEndpoint), ioTeasers())));
			}
			if (!this.pinnedBySite.isEmpty()) {
				out.add(new ProfileSummary.Section("pinning", "Virtual-thread pinning (pinned time, by site)", "ms",
						true, ranked(this.pinnedBySite, sum(this.pinnedBySite), this.pinnedReason)));
			}
			return out;
		}

		/** Per-endpoint teaser: humanized bytes + op count. */
		private Map<String, String> ioTeasers() {
			Map<String, String> teasers = new HashMap<>();
			this.ioByEndpoint.keySet()
				.forEach((ep) -> teasers.put(ep, humanBytes(this.ioBytes.getOrDefault(ep, 0L)) + " over "
						+ this.ioOps.getOrDefault(ep, 0L) + " ops"));
			return teasers;
		}

		/**
		 * App frames to detect the package from: CPU samples, or allocation sites if no
		 * CPU.
		 */
		private Map<String, Long> detectionWeights() {
			return this.cpuByApp.isEmpty() ? this.allocBySite : this.cpuByApp;
		}

		private String heuristic() {
			String topLock = top(this.lockByMethod);
			String topMon = top(this.lockByMonitor);
			String topAlloc = top(this.allocBySite);
			String topApp = top(this.cpuByApp);
			long gcMs = this.gcPauseNanos / 1_000_000;
			if (topLock != null && sum(this.lockByMethod) > 50_000_000L) {
				return "Lock contention — blocked time concentrated in `" + topLock + "`"
						+ ((topMon != null) ? " on a `" + topMon + "` monitor." : ".");
			}
			if (gcMs > 500 && topAlloc != null) {
				return "High allocation pressure (GC paused " + gcMs + " ms) — sustained allocation at `" + topAlloc
						+ "`" + ((this.oldObjects > 0) ? "; retained (old-object) samples suggest a leak." : ".");
			}
			if (topApp != null && this.execSamples > 0 && this.cpuByApp.get(topApp) * 100.0 / this.execSamples > 40) {
				return "CPU-bound — `" + topApp + "` accounts for the majority of samples.";
			}
			String topPinned = top(this.pinnedBySite);
			if (topPinned != null && sum(this.pinnedBySite) > 100_000_000L) {
				return "Virtual-thread pinning — carrier threads pinned at `" + topPinned
						+ "`; a synchronized block or native call is blocking the carrier.";
			}
			String topIo = top(this.ioByEndpoint);
			if (topApp == null && topIo != null) {
				return "I/O-bound — blocked time concentrated on `" + topIo + "`.";
			}
			return (topApp != null) ? "Hot path is `" + topApp + "`." : "No dominant signal.";
		}

	}

}
