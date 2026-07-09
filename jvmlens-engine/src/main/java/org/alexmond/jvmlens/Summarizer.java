package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import jdk.jfr.consumer.RecordedEvent;
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
		WEB,
		/** Top messaging operations (agent Kafka/JMS instrumentation). */
		MESSAGING,
		/** Top cache operations (agent Spring-Cache instrumentation). */
		CACHE,
		/** Top Micrometer timers (consumed from an existing registry). */
		METRICS,
		/** Deadlocked threads (agent ThreadMXBean check). */
		DEADLOCK

	}

	/**
	 * A 0-byte, single-op I/O endpoint blocked at least this long is almost certainly a
	 * child-process/pipe wait, not a network/DB peer (#121).
	 */
	private static final long PIPE_WAIT_NANOS = 1_000_000_000L;

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
		return analyze(List.of(file), scope, file.getFileName().toString());
	}

	/**
	 * Read one or more JFR recordings into a single merged summary — e.g. all the
	 * per-fork {@code .jfr} files JMH's {@code -prof jfr} writes for a benchmark, so the
	 * signal isn't split across forks.
	 * @param files the {@code .jfr} recordings to read and merge
	 * @param scope which frames count as application code
	 * @param source the label for the merged summary (e.g. the JMH run directory name)
	 * @return the structured summary
	 * @throws IOException if a recording cannot be read
	 */
	public static ProfileSummary analyze(List<Path> files, Scope scope, String source) throws IOException {
		return analyze(files, scope, source, 0L);
	}

	/**
	 * As {@link #analyze(List, Scope, String)}, but drops every event recorded in the
	 * first {@code skipWarmupMs} of each recording — so JIT-compilation/classload churn
	 * at startup doesn't contaminate the steady-state hot-path ranking (field-finding #53
	 * gap 4). The cutoff is measured <em>per file</em> from that file's earliest event,
	 * so each JMH fork (a fresh JVM) gets its own warmup trimmed.
	 * @param files the {@code .jfr} recordings to read and merge
	 * @param scope which frames count as application code
	 * @param source the label for the merged summary
	 * @param skipWarmupMs drop events in the first this-many ms of each recording (0 =
	 * keep all)
	 * @return the structured summary
	 * @throws IOException if a recording cannot be read
	 */
	public static ProfileSummary analyze(List<Path> files, Scope scope, String source, long skipWarmupMs)
			throws IOException {
		return analyze(files, scope, source, skipWarmupMs, false);
	}

	/**
	 * As {@link #analyze(List, Scope, String, long)}, but when {@code perRecording} is
	 * set and more than one recording is merged, also attaches a <em>per-recording</em>
	 * section: each source {@code .jfr}'s execution-sample count and its dominant hot
	 * paths. So a JMH {@code -prof jfr} run whose directory holds many benchmark methods
	 * shows which recording a hot path concentrates in, without a second single-file
	 * {@code analyze} pass (field-finding #153). A no-op for a single recording — it is
	 * already its own breakdown.
	 * @param files the {@code .jfr} recordings to read and merge
	 * @param scope which frames count as application code
	 * @param source the label for the merged summary
	 * @param skipWarmupMs drop events in the first this-many ms of each recording
	 * @param perRecording attach the per-recording breakdown section (multi-file only)
	 * @return the structured summary
	 * @throws IOException if a recording cannot be read
	 */
	public static ProfileSummary analyze(List<Path> files, Scope scope, String source, long skipWarmupMs,
			boolean perRecording) throws IOException {
		Aggregates agg = new Aggregates(scope);
		List<Teasers.PerRecording> perFile = (perRecording && files.size() > 1) ? new ArrayList<>() : null;
		for (Path file : files) {
			Instant cutoff = (skipWarmupMs > 0) ? recordingStart(file).plusMillis(skipWarmupMs) : null;
			Aggregates fileAgg = (perFile != null) ? new Aggregates(scope) : null;
			try (RecordingFile rf = new RecordingFile(file)) {
				while (rf.hasMoreEvents()) {
					RecordedEvent event = rf.readEvent();
					if (cutoff == null || !event.getStartTime().isBefore(cutoff)) {
						agg.add(event);
						if (fileAgg != null) {
							fileAgg.add(event);
						}
					}
				}
			}
			if (perFile != null) {
				perFile.add(new Teasers.PerRecording(file, fileAgg.execSamples,
						Teasers.topHotPaths(fileAgg.cpuByApp, fileAgg.execSamples)));
			}
		}
		ProfileSummary summary = agg.toSummary(source);
		return (perFile != null) ? summary.withSections(List.of(Teasers.perRecordingSection(perFile))) : summary;
	}

	/**
	 * The earliest event start in a recording — the warmup cutoff is measured from here.
	 */
	private static Instant recordingStart(Path file) throws IOException {
		Instant min = null;
		try (RecordingFile rf = new RecordingFile(file)) {
			while (rf.hasMoreEvents()) {
				Instant start = rf.readEvent().getStartTime();
				if (min == null || start.isBefore(min)) {
					min = start;
				}
			}
		}
		return (min != null) ? min : Instant.EPOCH;
	}

	/** The leaf (top-of-stack) java frame with its source line — self-time view (#87). */
	private static Frame leafFrameWithLine(RecordedStackTrace st) {
		return firstFrameWithLine(st, (owner) -> true);
	}

	/** The first application frame walking down from the leaf, per {@code scope}. */
	private static String appFrame(RecordedStackTrace st, Scope scope) {
		return firstFrame(st, scope::isApplication);
	}

	/**
	 * As {@link #appFrame}, but carrying the frame's source line (the alloc call site,
	 * #87).
	 */
	private static Frame appFrameWithLine(RecordedStackTrace st, Scope scope) {
		return firstFrameWithLine(st, scope::isApplication);
	}

	private static String firstFrame(RecordedStackTrace st, Predicate<String> ownerMatches) {
		Frame f = firstFrameWithLine(st, ownerMatches);
		return (f != null) ? f.method() : null;
	}

	private static Frame firstFrameWithLine(RecordedStackTrace st, Predicate<String> ownerMatches) {
		if (st == null) {
			return null;
		}
		return st.getFrames()
			.stream()
			.filter((f) -> f.isJavaFrame() && f.getMethod() != null)
			.filter((f) -> ownerMatches.test(f.getMethod().getType().getName()))
			.map((f) -> new Frame(f.getMethod().getType().getName() + "." + f.getMethod().getName(), f.getLineNumber()))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Accumulate {@code weight} against {@code line} in {@code key}'s line histogram
	 * (#87).
	 */
	private static void recordLine(Map<String, Map<Integer, Long>> lines, String key, int line, long weight) {
		if (line > 0) {
			lines.computeIfAbsent(key, (k) -> new HashMap<>()).merge(line, weight, Long::sum);
		}
	}

	/** The most-weighted source line in a histogram, or 0 if none was recorded (#87). */
	private static int dominantLine(Map<Integer, Long> hist) {
		if (hist == null || hist.isEmpty()) {
			return 0;
		}
		return hist.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
	}

	private static long sum(Map<String, Long> m) {
		return m.values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * Top-N rows of {@code m} as shares of {@code total}, newest-stack teaser attached.
	 * The row count is the runtime {@link RankLimits} for {@code category}.
	 */
	private static List<Ranked> ranked(Map<String, Long> m, long total, Map<String, String> stacks, String category) {
		List<Ranked> rows = new ArrayList<>();
		if (total <= 0) {
			return rows;
		}
		m.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(RankLimits.limit(category))
			.forEach((en) -> rows.add(new Ranked(en.getKey(), (double) en.getValue() / total, en.getValue(),
					(stacks != null) ? stacks.get(en.getKey()) : null)));
		return rows;
	}

	private static String top(Map<String, Long> m) {
		return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	/**
	 * Whether an I/O endpoint is infrastructure noise rather than application signal —
	 * the JFR recording's own sink (a {@code null}/unknown path, or the {@code .jfr} file
	 * itself), so neither the I/O section nor the cross-dimension correlation fires on
	 * the recorder. Surfaced by the gotmpl4j JMH field-finding (#39, gap 4).
	 */
	static boolean isNoiseEndpoint(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			return true;
		}
		return "unknown".equals(endpoint) || "file null".equals(endpoint) || "file unknown".equals(endpoint)
				|| endpoint.endsWith(".jfr");
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

	/**
	 * The I/O endpoint teaser ({@code "<bytes> over <ops> ops"}), with a
	 * child-process/pipe hint when the endpoint moved no bytes over a single op yet
	 * blocked for a long time — the shape of shelling out and waiting on a subprocess,
	 * which otherwise reads identically to a stalled network/DB peer (#121).
	 */
	static String ioTeaser(long bytes, long ops, long blockedNanos) {
		String teaser = humanBytes(bytes) + " over " + ops + " ops";
		if (bytes == 0 && ops == 1 && blockedNanos >= PIPE_WAIT_NANOS) {
			teaser += " — likely a child-process/pipe wait, not a network peer";
		}
		return teaser;
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
	/**
	 * Pick the single suspected-cause line, weighting each dimension by
	 * <em>magnitude</em> rather than mere presence. A lock is the headline only when its
	 * measured blocked time is substantial <em>and</em> exceeds the estimated CPU work
	 * and GC — so a small lock no longer outranks a real CPU hot path or large allocation
	 * (field-finding #67); a present but secondary lock is demoted to a hedged trailing
	 * note. {@code estCpuMs} is a rough estimate (sample count × ~10 ms ExecutionSample
	 * period) — only the order-of-magnitude comparison matters here.
	 */
	static String suspectedCause(CauseSignals s) {
		boolean lockDominates = s.topLock() != null && s.lockMs() >= 100 && s.lockMs() >= s.estCpuMs()
				&& s.lockMs() >= s.gcMs();
		String lockNote = (s.topLock() != null && s.lockMs() >= 5 && !lockDominates)
				? " Minor lock contention in `" + s.topLock() + "` (" + s.lockMs() + " ms)." : "";
		if (lockDominates) {
			return "Lock contention — blocked time concentrated in `" + s.topLock() + "`"
					+ ((s.topMonitor() != null) ? " on a `" + s.topMonitor() + "` monitor." : ".");
		}
		if (s.gcMs() > 500 && s.topAlloc() != null) {
			return "High allocation pressure (GC paused " + s.gcMs() + " ms) — sustained allocation at `" + s.topAlloc()
					+ "`" + ((s.oldObjects() > 0) ? "; retained (old-object) samples suggest a leak." : ".") + lockNote;
		}
		if (s.topApp() != null && s.topAppShare() > 40) {
			return "CPU-bound — `" + s.topApp() + "` accounts for the majority of samples." + lockNote;
		}
		if (s.topPinned() != null && s.pinnedMs() > 100) {
			return "Virtual-thread pinning — carrier threads pinned at `" + s.topPinned()
					+ "`; a synchronized block or native call is blocking the carrier." + lockNote;
		}
		if (s.topApp() == null && s.topIo() != null) {
			return "I/O-bound — blocked time concentrated on `" + s.topIo() + "`." + lockNote;
		}
		if (s.topApp() != null) {
			String alloc = (s.topAlloc() != null && s.allocMb() >= 50) ? "; top allocation at `" + s.topAlloc() + "`"
					: "";
			return "Hot path is `" + s.topApp() + "`" + alloc + "." + lockNote;
		}
		return "No dominant signal." + lockNote;
	}

	/**
	 * A resolved stack frame: its {@code Type.method} name and source line (≤0 if
	 * unknown).
	 */
	private record Frame(String method, int line) {
	}

	private static final class Aggregates {

		/** How many top allocation sites get a per-type breakdown teaser (#53 item 1). */
		private static final int ALLOC_TEASER_SITES = 2;

		/** How many types to list per site in that breakdown. */
		private static final int ALLOC_TEASER_TYPES = 3;

		/** How many top hot paths get a leaf-distribution teaser (#53 item 3). */
		private static final int LEAF_TEASER_PATHS = 5;

		private final Scope scope;

		private final Map<String, Long> cpuByApp = new HashMap<>();

		private final Map<String, Map<String, Long>> leafByApp = new HashMap<>();

		/** Per leaf/alloc-site method: source-line → weight, for line anchoring (#87). */
		private final Map<String, Map<Integer, Long>> leafLine = new HashMap<>();

		private final Map<String, Map<Integer, Long>> allocSiteLine = new HashMap<>();

		private final Map<String, Long> cpuByLeaf = new HashMap<>();

		private final Map<String, Long> allocBySite = new HashMap<>();

		private final Map<String, Long> allocByType = new HashMap<>();

		private final Map<String, Map<String, Long>> allocBySiteType = new HashMap<>();

		private final Map<String, Long> lockByMethod = new HashMap<>();

		private final Map<String, Long> lockByMonitor = new HashMap<>();

		private final Map<String, Long> ioByEndpoint = new HashMap<>();

		private final Map<String, Long> ioBytes = new HashMap<>();

		private final Map<String, Long> ioOps = new HashMap<>();

		private final Map<String, Long> pinnedBySite = new HashMap<>();

		private final Map<String, String> pinnedReason = new HashMap<>();

		private long execSamples;

		private long allocBytes;

		private long allocSamples;

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
			if (isNoiseEndpoint(endpoint)) {
				return; // recorder self-sink / unattributed I/O — not application signal
			}
			this.ioByEndpoint.merge(endpoint, e.getDuration().toNanos(), Long::sum);
			this.ioOps.merge(endpoint, 1L, Long::sum);
			long bytes = e.hasField(readField) ? e.getLong(readField)
					: (e.hasField(writeField) ? e.getLong(writeField) : 0);
			this.ioBytes.merge(endpoint, Math.max(bytes, 0), Long::sum);
		}

		private void addPinned(RecordedEvent e) {
			String site = appFrame(e.getStackTrace(), this.scope);
			if (site == null) {
				site = firstFrame(e.getStackTrace(), (owner) -> true);
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
			Frame leafF = leafFrameWithLine(e.getStackTrace());
			String leaf = (leafF != null) ? leafF.method() : null;
			if (leaf != null) {
				this.cpuByLeaf.merge(leaf, 1L, Long::sum);
				recordLine(this.leafLine, leaf, leafF.line(), 1L);
			}
			String app = appFrame(e.getStackTrace(), this.scope);
			if (app != null) {
				this.cpuByApp.merge(app, 1L, Long::sum);
				if (leaf != null) {
					this.leafByApp.computeIfAbsent(app, (k) -> new HashMap<>()).merge(leaf, 1L, Long::sum);
				}
			}
		}

		private void addAllocation(RecordedEvent e) {
			this.allocSamples++;
			long w = e.hasField("weight") ? e.getLong("weight") : 0;
			this.allocBytes += w;
			String type = (e.hasField("objectClass") && e.getClass("objectClass") != null)
					? e.getClass("objectClass").getName() : null;
			if (type != null) {
				this.allocByType.merge(type, w, Long::sum);
			}
			Frame siteF = appFrameWithLine(e.getStackTrace(), this.scope);
			String site = (siteF != null) ? siteF.method() : null;
			if (site != null) {
				this.allocBySite.merge(site, w, Long::sum);
				recordLine(this.allocSiteLine, site, siteF.line(), Math.max(w, 1));
				if (type != null) {
					this.allocBySiteType.computeIfAbsent(site, (k) -> new HashMap<>()).merge(type, w, Long::sum);
				}
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

		private ProfileSummary toSummary(String source) {
			return new ProfileSummary(source, this.execSamples, this.allocByType.size(), this.oldObjects, this.gcPauses,
					this.gcPauseNanos / 1_000_000, ranked(this.cpuByApp, this.execSamples, leafTeasers(), "cpu"),
					ranked(this.cpuByLeaf, this.execSamples, leafLineTeasers(), "cpu"),
					ranked(this.allocBySite, this.allocBytes, allocTypeTeasers(), "memory"),
					ranked(Teasers.foldExcludedTypes(this.allocByType, this.scope.excludePackages()), this.allocBytes,
							null, "memory"),
					ranked(this.lockByMethod, sum(this.lockByMethod), null, "locks"),
					ranked(this.lockByMonitor, sum(this.lockByMonitor), null, "locks"), heuristic(),
					detectAppPackage(detectionWeights()), extendedSections(), this.allocBytes, this.allocSamples);
		}

		/** The beyond-CPU/memory/wait dimensions, only those with any signal. */
		private List<ProfileSummary.Section> extendedSections() {
			List<ProfileSummary.Section> out = new ArrayList<>();
			if (!this.ioByEndpoint.isEmpty()) {
				out.add(new ProfileSummary.Section("io", "External I/O (blocked time, by endpoint)", "ms", true,
						ranked(this.ioByEndpoint, sum(this.ioByEndpoint), ioTeasers(), "io")));
			}
			if (!this.pinnedBySite.isEmpty()) {
				out.add(new ProfileSummary.Section("pinning", "Virtual-thread pinning (pinned time, by site)", "ms",
						true, ranked(this.pinnedBySite, sum(this.pinnedBySite), this.pinnedReason, "pinning")));
			}
			return out;
		}

		/**
		 * Per hot-path teaser: the top leaves (where time actually goes) with counts, via
		 * {@link Teasers#leafBreakdown}, instead of one possibly-unrepresentative
		 * first-seen stack — flagged {@code diffuse} when no single leaf dominates the
		 * path (#53 item 3).
		 */
		private Map<String, String> leafTeasers() {
			Map<String, String> teasers = new HashMap<>();
			this.cpuByApp.entrySet()
				.stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(LEAF_TEASER_PATHS)
				.forEach((path) -> {
					Map<String, Long> byLeaf = this.leafByApp.get(path.getKey());
					if (byLeaf != null && !byLeaf.isEmpty()) {
						teasers.put(path.getKey(), Teasers.leafBreakdown(withLines(byLeaf), path.getValue()));
					}
				});
			return teasers;
		}

		/**
		 * Decorate each leaf label with its dominant line ({@code method:line}); counts
		 * kept (#87).
		 */
		private Map<String, Long> withLines(Map<String, Long> byLeaf) {
			Map<String, Long> out = new HashMap<>();
			byLeaf.forEach((leaf, count) -> {
				int line = dominantLine(this.leafLine.get(leaf));
				out.put((line > 0) ? leaf + ":" + line : leaf, count);
			});
			return out;
		}

		/**
		 * Per hot-leaf row: a {@code line N} locator from the leaf's dominant line (#87).
		 */
		private Map<String, String> leafLineTeasers() {
			Map<String, String> teasers = new HashMap<>();
			this.leafLine.forEach((leaf, hist) -> {
				int line = dominantLine(hist);
				if (line > 0) {
					teasers.put(leaf, "line " + line);
				}
			});
			return teasers;
		}

		private Map<String, String> allocTypeTeasers() {
			Map<String, String> teasers = new HashMap<>();
			this.allocBySite.entrySet()
				.stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(ALLOC_TEASER_SITES)
				.forEach((site) -> {
					Map<String, Long> byType = this.allocBySiteType.get(site.getKey());
					if (byType != null && !byType.isEmpty()) {
						int line = dominantLine(this.allocSiteLine.get(site.getKey()));
						String prefix = (line > 0) ? ":" + line + " · " : "";
						teasers.put(site.getKey(), prefix + typeBreakdown(byType));
					}
				});
			// #103: flag any site dominated by an escape-analysis-prone type (boxed
			// primitive /
			// captured lambda) — C2 may scalar-replace non-escaping instances, so the
			// sampled
			// bytes can overstate steady-state allocation. Hedged; verify with `-prof
			// gc`.
			this.allocBySiteType.forEach((site, byType) -> {
				String dom = dominantType(byType);
				if (Teasers.escapeProneType(dom)) {
					String caveat = "⚠ " + Teasers.simpleType(dom)
							+ " may be scalar-replaced (escape analysis) — verify " + "steady-state with -prof gc";
					teasers.merge(site, caveat, (have, add) -> have + " " + add);
				}
			});
			return teasers;
		}

		/** The most-allocated type at a site, or {@code null} if none recorded. */
		private static String dominantType(Map<String, Long> byType) {
			return (byType == null || byType.isEmpty()) ? null
					: byType.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		}

		/** The top types of one site, formatted {@code Type bytes · Type bytes · …}. */
		private static String typeBreakdown(Map<String, Long> byType) {
			return byType.entrySet()
				.stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(ALLOC_TEASER_TYPES)
				.map((t) -> Teasers.simpleType(t.getKey()) + " " + humanBytes(t.getValue()))
				.collect(java.util.stream.Collectors.joining(" · "));
		}

		/** Per-endpoint teaser: humanized bytes + op count. */
		private Map<String, String> ioTeasers() {
			Map<String, String> teasers = new HashMap<>();
			this.ioByEndpoint.forEach((ep, nanos) -> teasers.put(ep,
					ioTeaser(this.ioBytes.getOrDefault(ep, 0L), this.ioOps.getOrDefault(ep, 0L), nanos)));
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
			String topApp = top(this.cpuByApp);
			double topShare = (topApp != null && this.execSamples > 0)
					? this.cpuByApp.get(topApp) * 100.0 / this.execSamples : 0.0;
			CauseSignals signals = new CauseSignals(sum(this.lockByMethod) / 1_000_000L, this.gcPauseNanos / 1_000_000L,
					this.allocBytes / (1024L * 1024L), this.execSamples * 10L, sum(this.pinnedBySite) / 1_000_000L,
					this.oldObjects, topApp, topShare, top(this.allocBySite), top(this.lockByMethod),
					top(this.lockByMonitor), top(this.ioByEndpoint), top(this.pinnedBySite));
			return suspectedCause(signals);
		}

	}

	/**
	 * The inputs the suspected-cause heuristic weighs, in comparable magnitudes (measured
	 * lock/GC/pinning in ms, allocation in MB, a rough CPU-work estimate in ms).
	 */
	record CauseSignals(long lockMs, long gcMs, long allocMb, long estCpuMs, long pinnedMs, long oldObjects,
			String topApp, double topAppShare, String topAlloc, String topLock, String topMonitor, String topIo,
			String topPinned) {
	}

}
