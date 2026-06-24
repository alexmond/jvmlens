package org.alexmond.jvmlens;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jdk.jfr.Recording;
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
