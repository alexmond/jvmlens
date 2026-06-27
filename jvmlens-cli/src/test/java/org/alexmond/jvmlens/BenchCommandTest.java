package org.alexmond.jvmlens;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import org.alexmond.jvmlens.testimpl.BenchWorkload;

import static org.assertj.core.api.Assertions.assertThat;

class BenchCommandTest {

	private static final String WORKLOAD = BenchWorkload.class.getName();

	@Test
	void rejectsNonPositiveIters() {
		int rc = new CommandLine(new BenchCommand()).execute("--main", WORKLOAD, "--iters", "0");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void rejectsNegativeWarmup() {
		int rc = new CommandLine(new BenchCommand()).execute("--main", WORKLOAD, "--warmup", "-1");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void rejectsNoAnalyzeWithoutJfr() {
		int rc = new CommandLine(new BenchCommand()).execute("--main", WORKLOAD, "--no-analyze");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void reportsUnloadableMainClass() {
		int rc = new CommandLine(new BenchCommand()).execute("--main", "no.such.Workload");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void reportsWorkloadFailure() {
		// a real class with no main(String[]) is rejected at resolve time (exit 2);
		// here we point at one whose main throws to hit the timed-run failure path (exit
		// 3).
		int rc = new CommandLine(new BenchCommand()).execute("--main", ThrowingWorkload.class.getName(), "-w", "0",
				"-i", "1");
		assertThat(rc).isEqualTo(3);
	}

	@Test
	void runsWorkloadAndPrintsSummary() {
		PrintStream originalOut = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			int rc = new CommandLine(new BenchCommand()).execute("--main", WORKLOAD, "-w", "2", "-i", "30");
			assertThat(rc).isZero();
		}
		finally {
			System.setOut(originalOut);
		}
		assertThat(captured.toString(StandardCharsets.UTF_8)).contains("# JVM profile summary");
	}

	@Test
	void keepsJfrWhenRequested(@TempDir Path tmp) throws Exception {
		Path jfr = tmp.resolve("bench.jfr");
		int rc = new CommandLine(new BenchCommand()).execute("--main", WORKLOAD, "-w", "1", "-i", "10", "--jfr",
				jfr.toString(), "--no-analyze");
		assertThat(rc).isZero();
		assertThat(Files.exists(jfr)).isTrue();
		assertThat(Files.size(jfr)).isPositive();
	}

	/** A workload whose main throws — drives the timed-run failure path (exit 3). */
	public static final class ThrowingWorkload {

		private ThrowingWorkload() {
		}

		public static void main(String[] args) {
			throw new IllegalStateException("boom");
		}

	}

}
