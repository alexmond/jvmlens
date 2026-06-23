package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
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

	@Option(names = { "-f", "--format" }, paramLabel = "<format>",
			description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
	Summarizer.Format format = Summarizer.Format.MARKDOWN;

	@Option(names = { "-a", "--app-package" }, paramLabel = "<prefix>", split = ",",
			description = "Treat only these package prefixes as application code (repeatable).")
	List<String> appPackages = new ArrayList<>();

	@Option(names = { "-x", "--exclude" }, paramLabel = "<prefix>", split = ",",
			description = "Extra package prefixes to treat as non-application code (repeatable).")
	List<String> excludePackages = new ArrayList<>();

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
		Scope scope = Scope.of(appPackages, excludePackages);
		try {
			LiveCapture.watch(pid, interval, maxAge, settings, snapshots, (snapshot, index) -> {
				try {
					System.out.println("=== snapshot " + index + " (last " + maxAge + "s) ===");
					System.out.print(Summarizer.summarize(snapshot, format, scope));
				}
				finally {
					Files.deleteIfExists(snapshot);
				}
			});
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

}
