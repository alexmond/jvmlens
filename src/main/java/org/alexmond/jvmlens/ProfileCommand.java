package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "profile", mixinStandardHelpOptions = true,
		description = "Attach to a running JVM, capture a timed JFR recording, and summarize it.")
public class ProfileCommand implements Callable<Integer> {

	@Parameters(index = "0", arity = "0..1", paramLabel = "<pid>",
			description = "Process ID of a local target JVM (omit when using --jmx).")
	String pid;

	@Option(names = { "--jmx" }, paramLabel = "<url>",
			description = "Remote JMX URL of the target JVM, e.g. service:jmx:rmi:///jndi/rmi://host:7091/jmxrmi "
					+ "(host:port also accepted). The remote JVM must be started with JMX remote enabled.")
	String jmx;

	@Option(names = { "-d", "--duration" }, paramLabel = "<seconds>",
			description = "Recording duration in seconds (default: ${DEFAULT-VALUE}).")
	int duration = 20;

	@Option(names = { "-w", "--warmup" }, paramLabel = "<seconds>",
			description = "Seconds to wait after attach before recording, to skip startup (default: ${DEFAULT-VALUE}).")
	int warmup;

	@Option(names = { "-s", "--settings" }, paramLabel = "<settings>",
			description = "JFR configuration: profile or default (default: ${DEFAULT-VALUE}).")
	String settings = "profile";

	@Option(names = { "-e", "--engine" }, paramLabel = "<engine>",
			description = "Capture engine: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}). "
					+ "async adds native frames but is local-pid only.")
	LiveCapture.Engine engine = LiveCapture.Engine.JFR;

	@Option(names = { "-k", "--keep" }, paramLabel = "<file>",
			description = "Keep the captured recording at this path instead of deleting it.")
	Path keep;

	@Mixin
	OutputOptions output;

	@Override
	public Integer call() throws Exception {
		boolean remote = jmx != null && !jmx.isEmpty();
		boolean local = pid != null && !pid.isEmpty();
		if (remote == local) {
			System.err.println("jvmlens: specify exactly one of <pid> or --jmx");
			return 2;
		}
		if (local && !pid.chars().allMatch(Character::isDigit)) {
			System.err.println("jvmlens: <pid> must be numeric: " + pid);
			return 2;
		}
		if (engine == LiveCapture.Engine.ASYNC && remote) {
			System.err.println("jvmlens: --engine async requires a local <pid>, not --jmx");
			return 2;
		}
		if (duration <= 0) {
			System.err.println("jvmlens: --duration must be positive: " + duration);
			return 2;
		}
		if (warmup < 0) {
			System.err.println("jvmlens: --warmup must not be negative: " + warmup);
			return 2;
		}
		Path recording;
		try {
			if (remote) {
				recording = LiveCapture.captureRemote(jmx, duration, settings, warmup);
			}
			else if (engine == LiveCapture.Engine.ASYNC) {
				recording = LiveCapture.captureAsync(pid, duration, warmup, "cpu");
			}
			else {
				recording = LiveCapture.capture(pid, duration, settings, warmup);
			}
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
			System.out.print(Summarizer.summarize(recording, output.format, output.scope(), output.report));
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
