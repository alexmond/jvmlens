package org.alexmond.jvmlens;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizerTest {

	@Test
	void summarizesCpuHotPath() throws Exception {
		Path file = cpuRecording();
		try {
			String md = Summarizer.summarize(file);
			assertThat(md).contains("# JVM profile summary");
			assertThat(md).contains("Top hot paths");
			assertThat(md).contains("SummarizerTest");
			assertThat(md).contains("Suspected cause");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void analyzeProducesStructuredSummary() throws Exception {
		Path file = cpuRecording();
		try {
			ProfileSummary s = Summarizer.analyze(file);
			assertThat(s.source()).isEqualTo(file.getFileName().toString());
			assertThat(s.execSamples()).isPositive();
			assertThat(s.hotPaths()).isNotEmpty();
			assertThat(s.hotPaths().get(0).name()).contains("SummarizerTest");
			assertThat(s.hotPaths().get(0).share()).isBetween(0.0, 1.0);
			assertThat(s.cause()).isNotBlank();
			assertThat(s.appPackage()).isEqualTo("org.alexmond");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void jsonFormatIsScopedAndStructured() throws Exception {
		Path file = cpuRecording();
		try {
			String json = Summarizer.summarize(file, Summarizer.Format.JSON);
			assertThat(json).startsWith("{");
			assertThat(json).contains("\"source\":");
			assertThat(json).contains("\"execSamples\":");
			assertThat(json).contains("\"hotPaths\":");
			assertThat(json).contains("\"share\":");
			assertThat(json).contains("\"cause\":");
			assertThat(json).contains("SummarizerTest");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void promptFormatWrapsMarkdown() throws Exception {
		Path file = cpuRecording();
		try {
			String prompt = Summarizer.summarize(file, Summarizer.Format.PROMPT);
			assertThat(prompt).contains("JVM performance expert");
			assertThat(prompt).contains("# JVM profile summary");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void summarizesAllocationAndLockContention() throws Exception {
		List<byte[]> keep = new ArrayList<>();
		Object lock = new Object();
		String md = record(() -> {
			long allocEnd = System.nanoTime() + 1_500_000_000L;
			while (System.nanoTime() < allocEnd) {
				byte[] block = new byte[16 * 1024];
				block[0] = 1;
				// Bounded retention (~16 MB): keep allocating to generate samples, but do
				// not retain everything or a small CI heap OOMs (forked test JVM).
				if (keep.size() < 1024) {
					keep.add(block);
				}
			}
			long lockEnd = System.nanoTime() + 1_500_000_000L;
			Thread[] workers = new Thread[8];
			for (int i = 0; i < workers.length; i++) {
				workers[i] = new Thread(() -> {
					while (System.nanoTime() < lockEnd) {
						synchronized (lock) {
							long until = System.nanoTime() + 5_000_000L;
							while (System.nanoTime() < until) {
								// hold the lock to force contention
							}
						}
					}
				});
				workers[i].start();
			}
			for (Thread t : workers) {
				t.join();
			}
		});
		assertThat(md).containsIgnoringCase("allocation");
		assertThat(md).containsIgnoringCase("lock");
		assertThat(keep).isNotEmpty();
	}

	@Test
	void summarizesFileIo() throws Exception {
		Recording recording = new Recording();
		recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
		recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
		recording.start();
		Path data = Files.createTempFile("jvmlens-io-data", ".bin");
		byte[] chunk = new byte[8192];
		try (FileOutputStream out = new FileOutputStream(data.toFile())) {
			for (int i = 0; i < 16; i++) {
				out.write(chunk);
			}
		}
		try (FileInputStream in = new FileInputStream(data.toFile())) {
			byte[] buf = new byte[8192];
			while (in.read(buf) >= 0) {
				// drain
			}
		}
		recording.stop();
		Path file = Files.createTempFile("jvmlens-io", ".jfr");
		recording.dump(file);
		recording.close();
		try {
			ProfileSummary s = Summarizer.analyze(file);
			boolean hasIo = s.sections().stream().anyMatch((sec) -> "io".equals(sec.key()));
			Assumptions.assumeTrue(hasIo, "JFR file-I/O events not captured on this platform");
			assertThat(Summarizer.summarize(file)).contains("External I/O").contains("file ");
			assertThat(Summarizer.summarize(file, Summarizer.Format.MARKDOWN, Scope.defaults(), Summarizer.Report.IO))
				.contains("External I/O")
				.doesNotContain("Top hot paths");
		}
		finally {
			Files.deleteIfExists(file);
			Files.deleteIfExists(data);
		}
	}

	@Test
	void filtersRecorderSelfSinkFromIo() {
		// #39 gap 4: a microbenchmark with no real I/O reported "file null" (the JFR
		// sink)
		assertThat(Summarizer.isNoiseEndpoint("file null")).isTrue();
		assertThat(Summarizer.isNoiseEndpoint("file unknown")).isTrue();
		assertThat(Summarizer.isNoiseEndpoint("unknown")).isTrue();
		assertThat(Summarizer.isNoiseEndpoint("/tmp/jvmlens-agent123.jfr")).isTrue();
		assertThat(Summarizer.isNoiseEndpoint("")).isTrue();
		assertThat(Summarizer.isNoiseEndpoint(null)).isTrue();
		// real endpoints are kept
		assertThat(Summarizer.isNoiseEndpoint("db-host:5432")).isFalse();
		assertThat(Summarizer.isNoiseEndpoint("file /var/data/orders.csv")).isFalse();
	}

	private static Path cpuRecording() throws Exception {
		return recordFile(() -> {
			long end = System.nanoTime() + 2_000_000_000L;
			while (System.nanoTime() < end) {
				hotLoop();
			}
		});
	}

	private static void hotLoop() {
		double x = 0;
		for (int i = 0; i < 50_000; i++) {
			x += Math.sqrt(i);
		}
		if (x < 0) {
			throw new IllegalStateException("unreachable");
		}
	}

	private static String record(Work work) throws Exception {
		Path file = recordFile(work);
		try {
			return Summarizer.summarize(file);
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	private static Path recordFile(Work work) throws Exception {
		Recording recording = new Recording();
		recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
		recording.enable("jdk.ObjectAllocationSample");
		recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
		recording.enable("jdk.GCPhasePause");
		recording.start();
		work.run();
		recording.stop();
		Path file = Files.createTempFile("jvmlens-test", ".jfr");
		recording.dump(file);
		recording.close();
		return file;
	}

	private interface Work {

		void run() throws Exception;

	}

}
