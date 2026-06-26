package org.alexmond.jvmlens;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileCommandTest {

	@Test
	void rejectsNonNumericPid() {
		int rc = new CommandLine(new ProfileCommand()).execute("notapid");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void rejectsNonPositiveDuration() {
		int rc = new CommandLine(new ProfileCommand()).execute("--duration", "0", "12345");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void rejectsNegativeWarmup() {
		int rc = new CommandLine(new ProfileCommand()).execute("--warmup", "-1", "12345");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void capturesWithAsyncProfilerEngine() throws Exception {
		Process target = startBusyJvm();
		Path recording = null;
		try {
			// itimer needs no perf_event access; but some sandboxes (CI) block loading
			// the
			// native agent into the target — skip there rather than fail.
			try {
				recording = LiveCapture.captureAsync(String.valueOf(target.pid()), 3, 0, "itimer");
			}
			catch (IOException ex) {
				Assumptions.abort("async-profiler unavailable in this environment: " + ex.getMessage());
			}
			ProfileSummary s = Summarizer.analyze(recording);
			assertThat(s.execSamples()).isPositive();
			assertThat(Renderers.markdown(s)).contains("BusyMain");
		}
		finally {
			if (recording != null) {
				Files.deleteIfExists(recording);
			}
			target.destroyForcibly();
			target.waitFor();
		}
	}

	@Test
	void reportsCaptureFailureForNonJvmPid() {
		// pid 1 (init) is not an attachable HotSpot JVM → capture fails with exit 3.
		int rc = new CommandLine(new ProfileCommand()).execute("--duration", "1", "1");
		assertThat(rc).isEqualTo(3);
	}

	@Test
	void capturesAndSummarizesLiveJvm() throws Exception {
		Process target = startBusyJvm();
		Path keep = Files.createTempFile("jvmlens-keep", ".jfr");
		Files.deleteIfExists(keep);
		PrintStream originalOut = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			int rc = new CommandLine(new ProfileCommand()).execute("-d", "4", "-k", keep.toString(),
					String.valueOf(target.pid()));
			assertThat(rc).isZero();
		}
		finally {
			System.setOut(originalOut);
			target.destroyForcibly();
			target.waitFor();
		}
		String out = captured.toString(StandardCharsets.UTF_8);
		assertThat(out).contains("# JVM profile summary").contains("BusyMain");
		assertThat(keep).exists();
		Files.deleteIfExists(keep);
	}

	private static Process startBusyJvm() throws Exception {
		String javaBin = System.getProperty("java.home") + "/bin/java";
		String classpath = System.getProperty("java.class.path");
		Process p = new ProcessBuilder(javaBin, "-cp", classpath, "org.alexmond.jvmlens.testimpl.BusyMain")
			.redirectErrorStream(true)
			.start();
		Thread.sleep(1500); // let the target spin up so the recording catches hot samples
		return p;
	}

}
