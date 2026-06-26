package org.alexmond.jvmlens;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class WatchCommandTest {

	@Test
	void rejectsNonNumericPid() {
		int rc = new CommandLine(new WatchCommand()).execute("notapid");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void rejectsNonPositiveInterval() {
		int rc = new CommandLine(new WatchCommand()).execute("--interval", "0", "12345");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void emitsPeriodicSummariesForLiveJvm() throws Exception {
		Process target = startBusyJvm();
		PrintStream originalOut = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			int rc = new CommandLine(new WatchCommand()).execute("-i", "3", "--max-age", "60", "-n", "2",
					String.valueOf(target.pid()));
			assertThat(rc).isZero();
		}
		finally {
			System.setOut(originalOut);
			target.destroyForcibly();
			target.waitFor();
		}
		String out = captured.toString(StandardCharsets.UTF_8);
		assertThat(out).contains("=== snapshot 1").contains("=== snapshot 2");
		assertThat(out).contains("BusyMain");
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
