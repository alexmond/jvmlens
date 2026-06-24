package org.alexmond.jvmlens.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import org.alexmond.jvmlens.Scope;
import org.alexmond.jvmlens.Summarizer;
import org.alexmond.jvmlens.snapshot.SnapshotCapture;
import org.alexmond.jvmlens.snapshot.SnapshotStore;

/**
 * In-process jvmlens agent: load it with {@code -javaagent:jvmlens-agent.jar} (or attach
 * it dynamically) and it keeps a continuous JFR ring buffer inside the target,
 * periodically writing a fresh LLM-ready summary to a file — no attach, no JMX,
 * container-native.
 *
 * <p>
 * Options are a comma-separated {@code key=value} list passed after {@code =}, e.g.
 * {@code -javaagent:jvmlens-agent.jar=out=/tmp/jvmlens.md,interval=60}. Keys: {@code out}
 * (summary file), {@code interval} (seconds between summaries), {@code settings} (JFR
 * config).
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
		int interval = Integer.parseInt(opts.getOrDefault("interval", "60"));
		String snapshot = opts.get("snapshot");
		if (snapshot != null && !snapshot.isBlank() && instrumentation != null) {
			SnapshotCapture.install(instrumentation, List.of(snapshot.split(";")));
			System.err.println("jvmlens-agent: capturing variable snapshots at " + snapshot);
		}
		Recording recording = new Recording(Configuration.getConfiguration(opts.getOrDefault("settings", "profile")));
		recording.setMaxAge(Duration.ofSeconds(Math.max(interval * 2L, 60)));
		recording.start();
		Thread worker = new Thread(() -> loop(recording, out, interval), "jvmlens-agent");
		worker.setDaemon(true);
		worker.start();
		System.err.println("jvmlens-agent: recording; summaries every " + interval + "s -> " + out);
	}

	private static void loop(Recording recording, Path out, int interval) {
		while (true) {
			try {
				Thread.sleep(interval * 1000L);
				snapshot(recording, out, Scope.defaults());
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

	/**
	 * Dump the current ring buffer, summarize it, and (atomically) write it to
	 * {@code out}.
	 */
	static void snapshot(Recording recording, Path out, Scope scope) throws Exception {
		Path dump = Files.createTempFile("jvmlens-agent", ".jfr");
		try {
			recording.dump(dump);
			String summary = Summarizer.summarize(dump, Summarizer.Format.MARKDOWN, scope);
			String snapshots = SnapshotStore.render();
			Files.writeString(out, snapshots.isEmpty() ? summary : summary + "\n" + snapshots);
		}
		finally {
			Files.deleteIfExists(dump);
		}
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
