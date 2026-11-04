package org.alexmond.jvmlens.jmh;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jdk.jfr.Recording;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.AverageTimeResult;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.IterationResultMetaData;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.WorkloadParams;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Drives {@link JvmlensProfiler#afterTrial} the way JMH's {@code Runner.runSeparate}
 * does: once per fork, warmup forks first, all with the same {@link BenchmarkParams}
 * (#191).
 */
final class ForkFixtures {

	static final String BENCH = "com.acme.Bench.run";

	private ForkFixtures() {
	}

	/** A small real JFR recording, so the summarizer has something to read. */
	static void record(Path out) throws Exception {
		try (Recording r = new Recording()) {
			r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
			r.start();
			long sink = 0;
			for (int i = 0; i < 200_000; i++) {
				sink += Integer.toString(i).hashCode();
			}
			r.stop();
			r.dump(out);
			if (sink == 42) {
				System.out.println(sink);
			}
		}
	}

	static BenchmarkParams params(int forks, int warmupForks) {
		IterationParams warmup = new IterationParams(IterationType.WARMUP, 1, TimeValue.seconds(1), 1);
		IterationParams measure = new IterationParams(IterationType.MEASUREMENT, 2, TimeValue.seconds(1), 1);
		return new BenchmarkParams(BENCH, BENCH + "_jmhTest", false, 1, new int[] { 1 }, List.of(), forks, warmupForks,
				warmup, measure, Mode.AverageTime, new WorkloadParams(), TimeUnit.NANOSECONDS, 1, "java", List.of(),
				"25", "vm", "1", "1.37", TimeValue.minutes(10));
	}

	/**
	 * One fork: two measurement iterations, both scoring {@code nsPerOp}, and a fresh
	 * copy of {@code recording} where the fork's JFR would have been dumped.
	 */
	static void fork(JvmlensProfiler p, Path recording, BenchmarkParams params, double nsPerOp) throws Exception {
		Files.copy(recording, jfrPath(p), StandardCopyOption.REPLACE_EXISTING);
		List<IterationResult> its = new java.util.ArrayList<>();
		for (int i = 0; i < 2; i++) {
			IterationResult it = new IterationResult(params, params.getMeasurement(),
					new IterationResultMetaData(1, 1));
			it.addResult(new AverageTimeResult(ResultRole.PRIMARY, "run", 1000, (long) (nsPerOp * 1000),
					TimeUnit.NANOSECONDS));
			its.add(it);
		}
		p.afterTrial(new BenchmarkResult(params, its), 1L, null, null);
	}

	/** The temp file the profiler armed JFR to dump into. */
	private static Path jfrPath(JvmlensProfiler p) {
		String opt = p.addJVMOptions(null).iterator().next();
		int at = opt.indexOf("filename=") + "filename=".length();
		return Path.of(opt.substring(at, opt.indexOf(',', at)));
	}

	static String captureOut(Body body) throws Exception {
		PrintStream old = System.out;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
		try {
			body.run();
		}
		finally {
			System.setOut(old);
		}
		return buf.toString(StandardCharsets.UTF_8);
	}

	@FunctionalInterface
	interface Body {

		void run() throws Exception;

	}

}
