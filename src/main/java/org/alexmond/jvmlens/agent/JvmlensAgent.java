package org.alexmond.jvmlens.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import org.alexmond.jvmlens.History;
import org.alexmond.jvmlens.ProfileSummary;
import org.alexmond.jvmlens.Scope;
import org.alexmond.jvmlens.Summarizer;
import org.alexmond.jvmlens.cache.CacheCapture;
import org.alexmond.jvmlens.cache.CacheStore;
import org.alexmond.jvmlens.messaging.MessagingCapture;
import org.alexmond.jvmlens.messaging.MessagingStore;
import org.alexmond.jvmlens.snapshot.SnapshotCapture;
import org.alexmond.jvmlens.snapshot.SnapshotStore;
import org.alexmond.jvmlens.sql.SqlCapture;
import org.alexmond.jvmlens.sql.SqlStore;
import org.alexmond.jvmlens.web.WebCapture;
import org.alexmond.jvmlens.web.WebStore;

/**
 * In-process jvmlens agent: load it with {@code -javaagent:jvmlens-agent.jar} (or attach
 * it dynamically) and it keeps a continuous JFR ring buffer inside the target,
 * periodically writing a fresh LLM-ready summary to a file — no attach, no JMX,
 * container-native.
 *
 * <p>
 * Options are a comma-separated {@code key=value} list passed after {@code =}, e.g.
 * {@code -javaagent:jvmlens-agent.jar=out=/tmp/jvmlens.md,interval=60}. Keys: {@code out}
 * (latest-summary file), {@code interval} (seconds between summaries), {@code settings}
 * (JFR config), {@code snapshot} (variable-snapshot targets), {@code db} (instrument JDBC
 * statement timing into a {@code Top SQL} section), {@code web} (HTTP servlet timing),
 * {@code messaging} (Kafka/JMS send + poll/receive timing), {@code cache} (Spring
 * {@code Cache} op timing), and {@code history} — a JSONL file the agent <em>appends</em>
 * one compact {@link History.Sample} to each interval, so a multi-day run can be reduced
 * to a trend later ({@code jvmlens trend}).
 */
public final class JvmlensAgent {

	private JvmlensAgent() {
	}

	/** Entry point for {@code -javaagent} at JVM launch. */
	public static void premain(String args, Instrumentation instrumentation) throws Exception {
		start(args, instrumentation);
	}

	/** Entry point for dynamic attach ({@code VirtualMachine.loadAgent}). */
	public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
		start(args, instrumentation);
	}

	private static void start(String args, Instrumentation instrumentation) throws Exception {
		Map<String, String> opts = parse(args);
		Path out = Path.of(opts.getOrDefault("out", "jvmlens-summary.md"));
		String historyOpt = opts.get("history");
		Path history = (historyOpt == null || historyOpt.isBlank()) ? null : Path.of(historyOpt);
		int interval = Integer.parseInt(opts.getOrDefault("interval", "60"));
		String snapshot = opts.get("snapshot");
		if (snapshot != null && !snapshot.isBlank() && instrumentation != null) {
			SnapshotCapture.install(instrumentation, List.of(snapshot.split(";")));
			System.err.println("jvmlens-agent: capturing variable snapshots at " + snapshot);
		}
		if (opts.containsKey("db") && instrumentation != null) {
			SqlCapture.install(instrumentation);
			System.err.println("jvmlens-agent: capturing JDBC statement timing");
		}
		if (opts.containsKey("web") && instrumentation != null) {
			WebCapture.install(instrumentation);
			System.err.println("jvmlens-agent: capturing HTTP endpoint timing");
		}
		if (opts.containsKey("messaging") && instrumentation != null) {
			MessagingCapture.install(instrumentation);
			System.err.println("jvmlens-agent: capturing messaging operation timing");
		}
		if (opts.containsKey("cache") && instrumentation != null) {
			CacheCapture.install(instrumentation);
			System.err.println("jvmlens-agent: capturing cache operation timing");
		}
		Recording recording = new Recording(Configuration.getConfiguration(opts.getOrDefault("settings", "profile")));
		recording.setMaxAge(Duration.ofSeconds(Math.max(interval * 2L, 60)));
		recording.start();
		Thread worker = new Thread(() -> loop(recording, out, history, interval), "jvmlens-agent");
		worker.setDaemon(true);
		worker.start();
		System.err.println("jvmlens-agent: recording; summaries every " + interval + "s -> " + out
				+ ((history != null) ? (" (history -> " + history + ")") : ""));
	}

	/**
	 * Scope for in-process summaries: like the default, but excludes jvmlens's own
	 * package so the agent never reports its own dump/summarize work as the target's hot
	 * path.
	 */
	static Scope agentScope() {
		return Scope.of(List.of(), List.of("org.alexmond.jvmlens"));
	}

	private static void loop(Recording recording, Path out, Path history, int interval) {
		while (true) {
			try {
				Thread.sleep(interval * 1000L);
				snapshot(recording, out, history, agentScope());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (Exception ex) {
				System.err.println("jvmlens-agent: snapshot failed: " + ex.getMessage());
			}
		}
	}

	/** Latest-only snapshot (no history) — kept for callers that don't track a run. */
	static void snapshot(Recording recording, Path out, Scope scope) throws Exception {
		snapshot(recording, out, null, scope);
	}

	/**
	 * Dump the current ring buffer, summarize it, write the latest markdown to
	 * {@code out}, and (when {@code history} is set) append one compact JSONL sample for
	 * the run.
	 */
	static void snapshot(Recording recording, Path out, Path history, Scope scope) throws Exception {
		Path dump = Files.createTempFile("jvmlens-agent", ".jfr");
		try {
			recording.dump(dump);
			ProfileSummary ps = Summarizer.analyze(dump, scope).withSections(instrumentationSections());
			String summary = Summarizer.render(ps, Summarizer.Format.MARKDOWN);
			String snapshots = SnapshotStore.render();
			Files.writeString(out, snapshots.isEmpty() ? summary : summary + "\n" + snapshots);
			if (history != null) {
				Files.writeString(history, History.toJsonLine(ps, System.currentTimeMillis()) + "\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
		finally {
			Files.deleteIfExists(dump);
		}
	}

	/**
	 * The extended sections produced by the agent's in-process instrumentation stores.
	 */
	private static List<ProfileSummary.Section> instrumentationSections() {
		List<ProfileSummary.Section> all = new ArrayList<>();
		all.addAll(SqlStore.sections());
		all.addAll(WebStore.sections());
		all.addAll(MessagingStore.sections());
		all.addAll(CacheStore.sections());
		return all;
	}

	private static Map<String, String> parse(String args) {
		Map<String, String> opts = new HashMap<>();
		if (args == null || args.isBlank()) {
			return opts;
		}
		for (String pair : args.split(",")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				opts.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
			}
		}
		return opts;
	}

}
