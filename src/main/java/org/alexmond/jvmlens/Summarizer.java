package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * The core of jvmlens: read a JFR recording and render a compact, LLM-targeted markdown
 * summary. A multi-megabyte {@code jfr print} dump collapses to roughly a few hundred
 * tokens of ranked, scoped, source-attributed signal.
 *
 * <p>
 * Dependency-free — uses only {@code jdk.jfr.consumer}.
 */
public final class Summarizer {

	/** Frames under these prefixes are runtime/library, not the app under study. */
	private static final String[] RUNTIME = { "java.", "jdk.", "sun.", "com.sun.", "javax.", "jakarta." };

	private Summarizer() {
	}

	/**
	 * Summarize a JFR recording into LLM-ready markdown.
	 * @param file the {@code .jfr} recording to read
	 * @return a compact markdown report
	 * @throws IOException if the recording cannot be read
	 */
	public static String summarize(Path file) throws IOException {
		Aggregates agg = new Aggregates();
		try (RecordingFile rf = new RecordingFile(file)) {
			while (rf.hasMoreEvents()) {
				agg.add(rf.readEvent());
			}
		}
		return agg.render(file);
	}

	/** First app frame (skipRuntime=true) or the deepest java frame (false). */
	private static String frame(RecordedStackTrace st, boolean skipRuntime) {
		if (st == null) {
			return null;
		}
		return st.getFrames()
			.stream()
			.filter((f) -> f.isJavaFrame() && f.getMethod() != null)
			.filter((f) -> !skipRuntime || !isRuntime(f.getMethod().getType().getName()))
			.map((f) -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
			.findFirst()
			.orElse(null);
	}

	private static boolean isRuntime(String owner) {
		for (String p : RUNTIME) {
			if (owner.startsWith(p)) {
				return true;
			}
		}
		return false;
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

	private static void section(StringBuilder md, String title, Map<String, Long> m, long total,
			Map<String, String> stacks) {
		md.append("## ").append(title).append('\n');
		if (m.isEmpty() || total <= 0) {
			md.append("- (none)\n\n");
			return;
		}
		m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).forEach((en) -> {
			md.append(String.format("- `%s` — %.0f%%", en.getKey(), 100.0 * en.getValue() / total));
			if (stacks != null && stacks.containsKey(en.getKey())) {
				md.append("  (").append(stacks.get(en.getKey())).append(')');
			}
			md.append('\n');
		});
		md.append('\n');
	}

	private static String top(Map<String, Long> m) {
		return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	/** Mutable accumulator for the events of a single recording. */
	private static final class Aggregates {

		private final Map<String, Long> cpuByApp = new HashMap<>();

		private final Map<String, String> appStack = new HashMap<>();

		private final Map<String, Long> cpuByLeaf = new HashMap<>();

		private final Map<String, Long> allocBySite = new HashMap<>();

		private final Map<String, Long> allocByType = new HashMap<>();

		private final Map<String, Long> lockByMethod = new HashMap<>();

		private final Map<String, Long> lockByMonitor = new HashMap<>();

		private long execSamples;

		private long allocBytes;

		private long oldObjects;

		private long gcPauses;

		private long gcPauseNanos;

		private void add(RecordedEvent e) {
			switch (e.getEventType().getName()) {
				case "jdk.ExecutionSample" -> addExecution(e);
				case "jdk.ObjectAllocationSample" -> addAllocation(e);
				case "jdk.OldObjectSample" -> {
					this.oldObjects++;
				}
				case "jdk.JavaMonitorEnter" -> addLock(e);
				case "jdk.GCPhasePause" -> addGc(e);
				default -> {
				}
			}
		}

		private void addExecution(RecordedEvent e) {
			this.execSamples++;
			String leaf = frame(e.getStackTrace(), false);
			if (leaf != null) {
				this.cpuByLeaf.merge(leaf, 1L, Long::sum);
			}
			String app = frame(e.getStackTrace(), true);
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
			String site = frame(e.getStackTrace(), true);
			if (site != null) {
				this.allocBySite.merge(site, w, Long::sum);
			}
		}

		private void addLock(RecordedEvent e) {
			long d = e.getDuration().toNanos();
			String m = frame(e.getStackTrace(), true);
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

		private String render(Path file) {
			StringBuilder md = new StringBuilder();
			md.append("# JVM profile summary (")
				.append(file.getFileName())
				.append(")\n\nEvents: ")
				.append(this.execSamples)
				.append(" exec samples, ")
				.append(this.allocByType.size())
				.append(" alloc types, ")
				.append(this.oldObjects)
				.append(" old-object samples, ")
				.append(this.gcPauses)
				.append(" GC pauses (")
				.append(this.gcPauseNanos / 1_000_000)
				.append(" ms).\n\n");
			section(md, "Top hot paths (application code, by sample share)", this.cpuByApp, this.execSamples,
					this.appStack);
			section(md, "Hot leaf methods (self-time, incl. runtime)", this.cpuByLeaf, this.execSamples, null);
			section(md, "Top allocation sites (application code, by est. bytes)", this.allocBySite, this.allocBytes,
					null);
			section(md, "Top allocated types (by est. bytes)", this.allocByType, this.allocBytes, null);
			section(md, "Lock contention (blocked time, by application method)", this.lockByMethod,
					sum(this.lockByMethod), null);
			if (!this.lockByMonitor.isEmpty()) {
				section(md, "Contended monitors", this.lockByMonitor, sum(this.lockByMonitor), null);
			}
			md.append("## Suspected cause (heuristic)\n- ").append(heuristic()).append('\n');
			return md.toString();
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
			return (topApp != null) ? "Hot path is `" + topApp + "`." : "No dominant signal.";
		}

	}

}
