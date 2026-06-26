package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "watch", mixinStandardHelpOptions = true,
		description = "Continuously record a running JVM (JFR ring buffer) and summarize a rolling window each interval.")
public class WatchCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<pid>", description = "Process ID of the target JVM.")
	String pid;

	@Option(names = { "-i", "--interval" }, paramLabel = "<seconds>",
			description = "Seconds between snapshot summaries (default: ${DEFAULT-VALUE}).")
	int interval = 30;

	@Option(names = { "--max-age" }, paramLabel = "<seconds>",
			description = "Ring-buffer window kept on the target (default: ${DEFAULT-VALUE}).")
	int maxAge = 120;

	@Option(names = { "-n", "--snapshots" }, paramLabel = "<count>",
			description = "Number of snapshots to take, or 0 to run until interrupted (default: ${DEFAULT-VALUE}).")
	int snapshots;

	@Option(names = { "-s", "--settings" }, paramLabel = "<settings>",
			description = "JFR configuration: profile or default (default: ${DEFAULT-VALUE}).")
	String settings = "profile";

	@Option(names = { "--on-gc-ms" }, paramLabel = "<ms>",
			description = "Only emit a snapshot when window GC pause time reaches this many ms.")
	long onGcMs;

	@Option(names = { "--on-cpu-pct" }, paramLabel = "<pct>",
			description = "Only emit when the top hot path reaches this sample share (0-100).")
	int onCpuPct;

	@Option(names = { "--on-old-objects" }, paramLabel = "<count>",
			description = "Only emit when retained (old-object) samples reach this count.")
	long onOldObjects;

	@Mixin
	OutputOptions output;

	@Override
	public Integer call() throws Exception {
		if (pid == null || pid.isEmpty() || !pid.chars().allMatch(Character::isDigit)) {
			System.err.println("jvmlens: <pid> must be numeric: " + pid);
			return 2;
		}
		if (interval <= 0 || maxAge <= 0) {
			System.err.println("jvmlens: --interval and --max-age must be positive");
			return 2;
		}
		Scope scope = output.scope();
		WatchTrigger trigger = new WatchTrigger(onGcMs, onCpuPct / 100.0, onOldObjects);
		LiveCapture.SnapshotSink sink = (snapshot, index) -> emit(snapshot, index, scope, trigger);
		try {
			LiveCapture.watch(pid, interval, maxAge, settings, snapshots, sink);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return 3;
		}
		catch (IOException | RuntimeException ex) {
			System.err.println("jvmlens: watch failed: " + ex.getMessage());
			return 3;
		}
		return 0;
	}

	private void emit(Path snapshot, int index, Scope scope, WatchTrigger trigger) throws IOException {
		try {
			ProfileSummary summary = Summarizer.analyze(snapshot, scope);
			if (trigger.active()) {
				String reason = trigger.reason(summary);
				if (reason == null) {
					return; // window did not breach any threshold — stay quiet
				}
				System.out.println("=== TRIGGERED: " + reason + " (snapshot " + index + ") ===");
			}
			else {
				System.out.println("=== snapshot " + index + " (last " + maxAge + "s) ===");
			}
			System.out.print(Summarizer.render(summary, output.format, output.report));
		}
		finally {
			Files.deleteIfExists(snapshot);
		}
	}

}
