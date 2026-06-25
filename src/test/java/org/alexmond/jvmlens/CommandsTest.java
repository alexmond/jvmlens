package org.alexmond.jvmlens;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsTest {

	@Test
	void analyzeReturnsZeroForValidRecording() throws Exception {
		Path file = tinyRecording();
		int rc = new CommandLine(new AnalyzeCommand()).execute(file.toString());
		Files.deleteIfExists(file);
		assertThat(rc).isZero();
	}

	@Test
	void analyzeReturnsTwoForMissingFile() {
		int rc = new CommandLine(new AnalyzeCommand()).execute("/no/such/file.jfr");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void analyzeAcceptsJsonFormatOption() throws Exception {
		Path file = tinyRecording();
		int rc = new CommandLine(new AnalyzeCommand()).setCaseInsensitiveEnumValuesAllowed(true)
			.execute("--format", "json", file.toString());
		Files.deleteIfExists(file);
		assertThat(rc).isZero();
	}

	@Test
	void analyzeRejectsUnknownFormat() throws Exception {
		Path file = tinyRecording();
		int rc = new CommandLine(new AnalyzeCommand()).execute("-f", "bogus", file.toString());
		Files.deleteIfExists(file);
		assertThat(rc).isEqualTo(2); // picocli usage error
	}

	@Test
	void analyzeAcceptsReportFocusOption() throws Exception {
		Path file = tinyRecording();
		int rc = new CommandLine(new AnalyzeCommand()).setCaseInsensitiveEnumValuesAllowed(true)
			.execute("--report", "cpu", file.toString());
		Files.deleteIfExists(file);
		assertThat(rc).isZero();
	}

	@Test
	void analyzeAcceptsAppPackageScopeOptions() throws Exception {
		Path file = tinyRecording();
		int rc = new CommandLine(new AnalyzeCommand()).execute("-a", "org.alexmond", "-x", "com.generated",
				file.toString());
		Files.deleteIfExists(file);
		assertThat(rc).isZero();
	}

	@Test
	void analyzeDiffsAgainstABaseline() throws Exception {
		Path before = tinyRecording();
		Path after = tinyRecording();
		java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
		java.io.PrintStream original = System.out;
		try {
			System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
			int rc = new CommandLine(new AnalyzeCommand()).execute("--baseline", before.toString(), after.toString());
			assertThat(rc).isZero();
		}
		finally {
			System.setOut(original);
			Files.deleteIfExists(before);
			Files.deleteIfExists(after);
		}
		assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("# JVM profile diff");
	}

	@Test
	void analyzeBaselineReturnsTwoForMissingBaseline() throws Exception {
		Path file = tinyRecording();
		int rc = new CommandLine(new AnalyzeCommand()).execute("--baseline", "/no/such/before.jfr", file.toString());
		Files.deleteIfExists(file);
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void assertNeedsBaseline() throws Exception {
		Path file = tinyRecording();
		int rc = new CommandLine(new AnalyzeCommand()).execute("--assert", "gc-ms < 1000", file.toString());
		Files.deleteIfExists(file);
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void assertPassesAndFailsByExitCode() throws Exception {
		Path before = tinyRecording();
		Path after = tinyRecording();
		try {
			// a lenient gate passes (exit 0)
			int pass = new CommandLine(new AnalyzeCommand()).execute("--baseline", before.toString(), "--assert",
					"gc-ms < 100000", after.toString());
			assertThat(pass).isZero();
			// an impossible gate fails (exit 1)
			int fail = new CommandLine(new AnalyzeCommand()).execute("--baseline", before.toString(), "--assert",
					"gc-ms < 0", after.toString());
			assertThat(fail).isEqualTo(1);
		}
		finally {
			Files.deleteIfExists(before);
			Files.deleteIfExists(after);
		}
	}

	@Test
	void rootCommandPrintsUsageAndReturnsZero() {
		int rc = new CommandLine(new JvmlensCommand()).execute();
		assertThat(rc).isZero();
	}

	private static Path tinyRecording() throws Exception {
		Recording recording = new Recording();
		recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
		recording.start();
		long end = System.nanoTime() + 300_000_000L;
		double x = 0;
		while (System.nanoTime() < end) {
			x += Math.sqrt(x + 1);
		}
		recording.stop();
		Path file = Files.createTempFile("jvmlens-cmd", ".jfr");
		recording.dump(file);
		recording.close();
		if (x < 0) {
			throw new IllegalStateException("unreachable");
		}
		return file;
	}

}
