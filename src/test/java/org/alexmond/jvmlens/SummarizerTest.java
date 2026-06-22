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
		String md = record(() -> {
			long end = System.nanoTime() + 2_000_000_000L;
			while (System.nanoTime() < end) {
				hotLoop();
			}
		});
		assertThat(md).contains("# JVM profile summary");
		assertThat(md).contains("Top hot paths");
		assertThat(md).contains("SummarizerTest");
		assertThat(md).contains("Suspected cause");
	}

	@Test
	void summarizesAllocationAndLockContention() throws Exception {
		List<byte[]> keep = new ArrayList<>();
		Object lock = new Object();
		String md = record(() -> {
			long allocEnd = System.nanoTime() + 1_500_000_000L;
			while (System.nanoTime() < allocEnd) {
				keep.add(new byte[32 * 1024]);
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
		String md = Summarizer.summarize(file);
		Files.deleteIfExists(file);
		return md;
	}

	private interface Work {

		void run() throws Exception;

	}

}
