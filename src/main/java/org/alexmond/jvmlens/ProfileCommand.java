package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "profile", mixinStandardHelpOptions = true,
		description = "Attach to a running JVM, capture a timed JFR recording, and summarize it.")
public class ProfileCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<pid>", description = "Process ID of the target JVM.")
	String pid;

	@Option(names = { "-d", "--duration" }, paramLabel = "<seconds>",
			description = "Recording duration in seconds (default: ${DEFAULT-VALUE}).")
	int duration = 20;

	@Option(names = { "-s", "--settings" }, paramLabel = "<settings>",
			description = "JFR configuration: profile or default (default: ${DEFAULT-VALUE}).")
	String settings = "profile";

	@Option(names = { "-f", "--format" }, paramLabel = "<format>",
			description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
	Summarizer.Format format = Summarizer.Format.MARKDOWN;

	@Option(names = { "-k", "--keep" }, paramLabel = "<file>",
			description = "Keep the captured recording at this path instead of deleting it.")
	Path keep;

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
		if (duration <= 0) {
			System.err.println("jvmlens: --duration must be positive: " + duration);
			return 2;
		}
		Path recording;
		try {
			recording = LiveCapture.capture(pid, duration, settings);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			System.err.println("jvmlens: capture interrupted");
			return 3;
		}
		catch (IOException | RuntimeException ex) {
			System.err.println("jvmlens: capture failed: " + ex.getMessage());
			return 3;
		}
		try {
			System.out.print(Summarizer.summarize(recording, format, Scope.of(appPackages, excludePackages)));
		}
		finally {
			finish(recording);
		}
		return 0;
	}

	private void finish(Path recording) throws IOException {
		if (keep != null) {
			Files.move(recording, keep, StandardCopyOption.REPLACE_EXISTING);
			System.err.println("jvmlens: recording kept at " + keep);
		}
		else {
			Files.deleteIfExists(recording);
		}
	}

}
