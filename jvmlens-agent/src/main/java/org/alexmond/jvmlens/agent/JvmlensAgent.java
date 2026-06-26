package org.alexmond.jvmlens.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import org.alexmond.jvmlens.History;
import org.alexmond.jvmlens.ProfileSummary;
import org.alexmond.jvmlens.Scope;
import org.alexmond.jvmlens.Summarizer;
import org.alexmond.jvmlens.cache.CacheCapture;
import org.alexmond.jvmlens.cache.CacheStore;
import org.alexmond.jvmlens.consume.MicrometerSource;
import org.alexmond.jvmlens.deadlock.DeadlockDetector;
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
 * (JFR config), {@code snapshot} (variable-snapshot targets), {@code db} / {@code web} /
 * {@code messaging} / {@code cache} (instrumentation dimensions), {@code micrometer}
 * (summarize an existing registry), {@code history} (a JSONL file the agent appends one
 * {@link History.Sample} to each interval), {@code paused} (launch without emitting —
 * start it after warm-up to skip startup noise), and {@code control} (a file the agent
 * watches for in-flight commands; see {@link AgentControl}).
 */
public final class JvmlensAgent {

	/**
	 * ByteBuddy-installed dimensions (so a runtime {@code enable} never
	 * double-instruments).
	 */
	private static final Set<String> INSTALLED = ConcurrentHashMap.newKeySet();

	private static Instrumentation instr;

	private static AgentControl control;

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
		instr = instrumentation;
		Path out = Path.of(opts.getOrDefault("out", "jvmlens-summary.md"));
		String historyOpt = opts.get("history");
		Path history = (historyOpt == null || historyOpt.isBlank()) ? null : Path.of(historyOpt);
		int interval = Integer.parseInt(opts.getOrDefault("interval", "60"));
		String settings = opts.getOrDefault("settings", "profile");

		String snapshot = opts.get("snapshot");
		if (snapshot != null && !snapshot.isBlank() && instrumentation != null) {
			SnapshotCapture.install(instrumentation, List.of(snapshot.split(";")));
			INSTALLED.add("snapshot");
			System.err.println("jvmlens-agent: capturing variable snapshots at " + snapshot);
		}

		Set<String> enabled = new HashSet<>();
		enabled.add("deadlock"); // always-on — a deadlock is the top signal
		for (String dim : new String[] { "db", "web", "messaging", "cache" }) {
			if (opts.containsKey(dim)) {
				enabled.add(dim);
				lazyInstall(dim);
			}
		}
		if (opts.containsKey("micrometer")) {
			enabled.add("micrometer");
		}
		if (snapshot != null && !snapshot.isBlank()) {
			enabled.add("snapshot");
		}

		boolean running = !opts.containsKey("paused");
		control = new AgentControl(running, settings, interval, enabled, List.of("org.alexmond.jvmlens"),
				JvmlensAgent::lazyInstall);

		String controlFile = opts.get("control");
		if (controlFile != null && !controlFile.isBlank()) {
			new ControlChannel(Path.of(controlFile), control).start();
			System.err.println("jvmlens-agent: control file -> " + controlFile);
		}

		Recording recording = newRecording(settings, interval);
		recording.start();
		Thread worker = new Thread(() -> loop(recording, out, history), "jvmlens-agent");
		worker.setDaemon(true);
		worker.start();
		System.err.println("jvmlens-agent: " + (running ? "recording" : "PAUSED") + "; summaries every " + interval
				+ "s -> " + out + ((history != null) ? (" (history -> " + history + ")") : ""));
	}

	/** Install a dimension's ByteBuddy advice once (no-op if already installed). */
	private static void lazyInstall(String dim) {
		if (instr == null || !INSTALLED.add(dim)) {
			return;
		}
		switch (dim) {
			case "db" -> SqlCapture.install(instr);
			case "web" -> WebCapture.install(instr);
			case "messaging" -> MessagingCapture.install(instr);
			case "cache" -> CacheCapture.install(instr);
			default -> {
				return; // micrometer / snapshot / deadlock have nothing to install here
			}
		}
		System.err.println("jvmlens-agent: instrumented " + dim);
	}

	/** Scope for in-process summaries — excludes jvmlens's own package. */
	static Scope agentScope() {
		return Scope.of(List.of(), List.of("org.alexmond.jvmlens"));
	}

	private static Recording newRecording(String settings, int interval) throws Exception {
		Recording recording = new Recording(Configuration.getConfiguration(settings));
		recording.setMaxAge(Duration.ofSeconds(Math.max(interval * 2L, 60)));
		return recording;
	}

	private static void loop(Recording initial, Path out, Path history) {
		Recording recording = initial;
		while (true) {
			try {
				String pending = control.takePendingSettings();
				if (pending != null) {
					recording = restart(recording, pending);
				}
				if (control.takeClear()) {
					resetStores();
					recording = restart(recording, control.settings());
				}
				boolean dump = sleepInterval();
				if (control.running() || dump) {
					snapshot(recording, out, history, control.scope());
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				recording.close();
				return;
			}
			catch (Exception ex) {
				System.err.println("jvmlens-agent: snapshot failed: " + ex.getMessage());
			}
		}
	}

	/**
	 * Sleep up to the current interval in 1s steps; returns true if a dump was requested.
	 */
	private static boolean sleepInterval() throws InterruptedException {
		int target = Math.max(control.interval(), 1);
		for (int s = 0; s < target; s++) {
			Thread.sleep(1000);
			if (control.takeDump()) {
				return true;
			}
		}
		return false;
	}

	/** Start a fresh recording with {@code settings}, then close the old one. */
	private static Recording restart(Recording old, String settings) {
		Recording fresh;
		try {
			fresh = newRecording(settings, control.interval());
			fresh.start();
		}
		catch (Exception ex) {
			System.err.println("jvmlens-agent: settings change failed: " + ex.getMessage());
			return old;
		}
		try {
			old.close();
		}
		catch (Exception ignored) {
			// best-effort close of the superseded recording
		}
		System.err.println("jvmlens-agent: recording restarted (settings=" + settings + ")");
		return fresh;
	}

	private static void resetStores() {
		SqlStore.reset();
		WebStore.reset();
		MessagingStore.reset();
		CacheStore.reset();
		SnapshotStore.reset();
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

	/** The extended sections, gated by the current {@link AgentControl} enable state. */
	private static List<ProfileSummary.Section> instrumentationSections() {
		List<ProfileSummary.Section> all = new ArrayList<>();
		if (control == null) {
			return all;
		}
		if (control.enabled("deadlock")) {
			all.addAll(DeadlockDetector.detect());
		}
		if (control.enabled("db")) {
			all.addAll(SqlStore.sections());
		}
		if (control.enabled("web")) {
			all.addAll(WebStore.sections());
		}
		if (control.enabled("messaging")) {
			all.addAll(MessagingStore.sections());
		}
		if (control.enabled("cache")) {
			all.addAll(CacheStore.sections());
		}
		if (control.enabled("micrometer")) {
			all.addAll(MicrometerSource.readGlobal());
		}
		return all;
	}

	private static Map<String, String> parse(String args) {
		Map<String, String> opts = new HashMap<>();
		if (args == null || args.isBlank()) {
			return opts;
		}
		for (String pair : args.split(",")) {
			int eq = pair.indexOf('=');
			opts.put((eq > 0) ? pair.substring(0, eq).trim() : pair.trim(),
					(eq > 0) ? pair.substring(eq + 1).trim() : "");
		}
		return opts;
	}

}
