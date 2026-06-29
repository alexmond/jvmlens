package org.alexmond.jvmlens;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	@Test
	void mergesMultipleRecordingsIntoOneSummary() throws Exception {
		Path one = cpuRecording();
		Path two = cpuRecording();
		try {
			ProfileSummary single = Summarizer.analyze(one);
			ProfileSummary merged = Summarizer.analyze(List.of(one, two), Scope.defaults(), "jmh-run");
			assertThat(merged.source()).isEqualTo("jmh-run");
			// merging two forks accumulates the signal
			assertThat(merged.execSamples()).isGreaterThanOrEqualTo(single.execSamples());
			assertThat(merged.hotPaths().get(0).name()).contains("SummarizerTest");
		}
		finally {
			Files.deleteIfExists(one);
			Files.deleteIfExists(two);
		}
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

	@Test
	void crossTabsTypePerAllocationSite() throws Exception {
		// Pre-size so the ArrayList's own Object[] resizes don't compete with byte[] as
		// an
		// allocation source — byte[16K] should be the sole sampled allocator here.
		List<byte[]> keep = new ArrayList<>(1024);
		Path file = recordFile(() -> {
			long end = System.nanoTime() + 1_500_000_000L;
			while (System.nanoTime() < end) {
				byte[] block = new byte[16 * 1024];
				block[0] = 1;
				if (keep.size() < 1024) {
					keep.add(block);
				}
			}
		});
		try {
			ProfileSummary s = Summarizer.analyze(file);
			// JFR allocation sampling is statistical; a short window on a loaded CI
			// runner can
			// occasionally reshuffle ranking or miss the site — find the byte[] row
			// rather
			// than assuming it ranks #0, and skip (don't fail) if sampling missed it
			// entirely.
			java.util.Optional<ProfileSummary.Ranked> byteSite = s.allocSites()
				.stream()
				.filter((r) -> r.stack() != null && r.stack().contains("byte[]"))
				.findFirst();
			Assumptions.assumeTrue(byteSite.isPresent(),
					"no byte[] allocation site sampled in the window (sparse JFR allocation sampling)");
			String teaser = byteSite.get().stack();
			// the site carries a per-type breakdown teaser (#53 item 1) prefixed with the
			// allocation call-site's source line (#87)
			assertThat(teaser).containsPattern(":\\d+ · ");
			// byte[] is real allocation — NOT flagged as escape-analysis-prone (#103)
			assertThat(teaser).doesNotContain("scalar-replaced");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void suspectedCauseDoesNotLetAMinorLockOutrankCpuAndAllocation() {
		// #67: a 62ms lock must not be the headline over a 7% hot path + ~200MB
		// allocation
		// (estCpuMs = 1182 samples × 10 ≫ 62ms). Lead with the real lever, hedge the
		// lock.
		String cause = Summarizer.suspectedCause(new Summarizer.CauseSignals(62, 10, 200, 11_820, 0, 0,
				"com.acme.BranchService.summarize", 7.0, "com.acme.ReportingService.recentRuns",
				"com.acme.TestRegressionService.diff", "[I", null, null));
		assertThat(cause).doesNotStartWith("Lock contention")
			.contains("Hot path is `com.acme.BranchService.summarize`")
			.contains("top allocation at `com.acme.ReportingService.recentRuns`")
			.contains("Minor lock contention in `com.acme.TestRegressionService.diff` (62 ms)");
	}

	@Test
	void suspectedCauseStillLeadsWithAGenuinelyDominantLock() {
		// a large lock that exceeds the estimated CPU work IS the headline (no hedge
		// note).
		String cause = Summarizer.suspectedCause(new Summarizer.CauseSignals(5_000, 0, 10, 2_000, 0, 0,
				"com.acme.Svc.run", 10.0, "com.acme.Svc.run", "com.acme.Svc.lock", "com.acme.Mutex", null, null));
		assertThat(cause).startsWith("Lock contention").contains("`com.acme.Svc.lock`").doesNotContain("Minor lock");
	}

	@Test
	void suspectedCauseNamesCpuWhenItIsTheMajority() {
		String cause = Summarizer.suspectedCause(new Summarizer.CauseSignals(0, 0, 0, 5_000, 0, 0, "com.acme.Svc.run",
				80.0, null, null, null, null, null));
		assertThat(cause).isEqualTo("CPU-bound — `com.acme.Svc.run` accounts for the majority of samples.");
	}

	@Test
	void anchorsHotRowsWithSourceLines() throws Exception {
		// #87: a pure-arithmetic app loop → the leaf is app code with a known line.
		Path file = recordFile(() -> {
			long end = System.nanoTime() + 2_000_000_000L;
			long acc = 1;
			while (System.nanoTime() < end) {
				acc = lineChurn(acc);
			}
			if (acc == 0) {
				throw new IllegalStateException("unreachable");
			}
		});
		try {
			String md = Summarizer.summarize(file);
			// the hot-path leaf teaser carries the leaf's source line (method:line
			// c/total)
			assertThat(md).containsPattern(":\\d+ \\d+/\\d+");
			// the hot-leaf row carries a `(line N)` locator
			assertThat(md).containsPattern("\\(line \\d+\\)");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	private static long lineChurn(long seed) {
		long acc = seed;
		for (int i = 1; i < 200_000; i++) {
			acc += (i * 2654435761L) ^ (acc >>> 7);
		}
		return acc;
	}

	@Test
	void hotPathTeaserShowsLeafDistributionWithCounts() throws Exception {
		Path file = cpuRecording();
		try {
			ProfileSummary s = Summarizer.analyze(file);
			String teaser = s.hotPaths().get(0).stack();
			// #53 item 3: the teaser names the leaf(es) where time actually goes, with
			// per-leaf counts as a fraction of the path's samples (c/total) — not one
			// possibly-unrepresentative first-seen stack.
			assertThat(teaser).isNotBlank().containsPattern("\\d+/\\d+");
			// a tight CPU loop concentrates on one leaf, so it is NOT flagged diffuse.
			assertThat(teaser).doesNotContain("diffuse");
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void diffuseHotPathIsFlaggedLowConfidence() {
		// when no single leaf holds ≥20% of the path's samples, the teaser warns the
		// reader not to chase any one frame (the jhelm URLClassPath.getResource case).
		Map<String, Long> byLeaf = new LinkedHashMap<>();
		byLeaf.put("a.B.x", 30L);
		byLeaf.put("a.B.y", 28L);
		byLeaf.put("a.B.z", 25L);
		String teaser = Teasers.leafBreakdown(byLeaf, 168L);
		assertThat(teaser).contains("a.B.x 30/168").contains("diffuse").contains("20%");
	}

	@Test
	void flagsEscapeAnalysisProneAllocationTypes() {
		// #103: boxed primitives + captured lambdas are C2 scalar-replacement candidates
		// —
		// a hot site dominated by them may be a false lever (sampled, but eliminated at
		// C2).
		assertThat(Teasers.escapeProneType("java.lang.Integer")).isTrue();
		assertThat(Teasers.escapeProneType("java.lang.Double")).isTrue();
		assertThat(Teasers.escapeProneType("com.foo.Bar$$Lambda$1/0x0000")).isTrue();
		// real allocations are not flagged
		assertThat(Teasers.escapeProneType("java.lang.String")).isFalse();
		assertThat(Teasers.escapeProneType("[B")).isFalse();
		assertThat(Teasers.escapeProneType(null)).isFalse();
	}

	@Test
	void skipWarmupDropsEarlyEvents() throws Exception {
		Path file = cpuRecording(); // ~2s of CPU samples
		try {
			long full = Summarizer.analyze(List.of(file), Scope.defaults(), "x", 0L).execSamples();
			// a cutoff far past the recording's span drops every event (#53 gap 4)
			long trimmed = Summarizer.analyze(List.of(file), Scope.defaults(), "x", 60_000L).execSamples();
			assertThat(full).isPositive();
			assertThat(trimmed).isZero();
		}
		finally {
			Files.deleteIfExists(file);
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
