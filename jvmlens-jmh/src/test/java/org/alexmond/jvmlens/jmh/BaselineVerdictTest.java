package org.alexmond.jvmlens.jmh;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code baseline=} measured A/B verdict end to end, through the profiler's option
 * string (#207): a baseline from another {@code @Param}/mode variant is skipped, and a
 * missing JMH error band is unknown, never SIGNIFICANT.
 */
class BaselineVerdictTest {

	/**
	 * Keep a run of {@code params} at {@code nsPerOp} per fork; returns the kept path.
	 */
	private static Path keep(Path tmp, Path recording, BenchmarkParams params, double... nsPerOp) throws Exception {
		Path kept = tmp.resolve("base.jfr");
		JvmlensProfiler p = new JvmlensProfiler("keep=" + kept);
		ForkFixtures.captureOut(() -> {
			for (double ns : nsPerOp) {
				ForkFixtures.fork(p, recording, params, ns);
			}
		});
		return kept;
	}

	/**
	 * Run {@code params} against {@code baseline} and return what the profiler printed.
	 */
	private static String against(Path baseline, Path recording, BenchmarkParams params, double... nsPerOp)
			throws Exception {
		JvmlensProfiler p = new JvmlensProfiler("baseline=" + baseline);
		return ForkFixtures.captureOut(() -> {
			for (double ns : nsPerOp) {
				ForkFixtures.fork(p, recording, params, ns);
			}
		});
	}

	private static Path recording(Path tmp) throws Exception {
		Path recording = tmp.resolve("rec.jfr");
		ForkFixtures.record(recording);
		return recording;
	}

	@Test
	void aBaselineKeptForAnotherParamValueIsSkippedWithAWarning(@TempDir Path tmp) throws Exception {
		Path recording = recording(tmp);
		Path base = keep(tmp, recording, ForkFixtures.params(2, 0, "n", "10"), 100.0, 110.0);
		String out = against(base, recording, ForkFixtures.params(2, 0, "n", "1000"), 400.0, 410.0);
		assertThat(out).contains("Baseline mismatch").contains("n-10").contains("n-1000");
		assertThat(out).doesNotContain("Measured throughput A/B");
	}

	@Test
	void aBaselineKeptForTheSameParamValueStillGetsTheVerdict(@TempDir Path tmp) throws Exception {
		// look-alike: same @Param value, including characters URL encoding changes
		Path recording = recording(tmp);
		Path base = keep(tmp, recording, ForkFixtures.params(2, 0, "n", "a b/c+d"), 100.0, 110.0);
		String out = against(base, recording, ForkFixtures.params(2, 0, "n", "a b/c+d"), 101.0, 109.0);
		assertThat(out).contains("Measured throughput A/B").doesNotContain("mismatch").doesNotContain("could not be");
	}

	@Test
	void aBaselineFromAnotherModeIsSkippedWithAWarning(@TempDir Path tmp) throws Exception {
		Path recording = recording(tmp);
		Path base = tmp.resolve("base.jfr");
		Files.copy(recording, base);
		String thrptId = ForkFixtures.params(2, 0, Mode.Throughput).id();
		Files.writeString(Path.of(base + ".bop"), "benchmark=" + ForkFixtures.BENCH + " id=" + thrptId
				+ " mforks=2 tput=221.6 tputerr=10.0 tputunit=ops/us");
		String out = against(base, recording, ForkFixtures.params(2, 0), 220.0, 230.0);
		assertThat(out).contains("Baseline mismatch").doesNotContain("Measured throughput A/B");
	}

	@Test
	void aLegacySidecarWithoutAnIdWarnsThatTheMatchCouldNotBeChecked(@TempDir Path tmp) throws Exception {
		Path recording = recording(tmp);
		Path base = tmp.resolve("base.jfr");
		Files.copy(recording, base);
		Files.writeString(Path.of(base + ".bop"),
				"benchmark=" + ForkFixtures.BENCH + " mforks=2 tput=221.6 tputerr=10.0 tputunit=ns/op");
		String out = against(base, recording, ForkFixtures.params(2, 0, "n", "10"), 220.0, 230.0);
		assertThat(out).contains("could not be checked").contains("Measured throughput A/B");
	}

	@Test
	void aMissingErrorBandIsUnknownNeverSignificant(@TempDir Path tmp) throws Exception {
		// one fork of two iterations: JMH reports the score error as NaN (N <= 2)
		Path recording = recording(tmp);
		BenchmarkParams params = ForkFixtures.params(1, 0);
		Path base = keep(tmp, recording, params, 100.0);
		assertThat(Files.readString(Path.of(base + ".bop"))).contains("tputerr=nan").doesNotContain("tputerr=0");
		String out = against(base, recording, params, 200.0);
		assertThat(out).contains("Measured throughput A/B").contains("error band unavailable").contains("iterations");
		assertThat(out).doesNotContain("SIGNIFICANT")
			.doesNotContain("NOT significant")
			.doesNotContain("NaN")
			.doesNotContain("is flat");
	}

	@Test
	void aLegacyZeroErrorIsReadAsUnknown(@TempDir Path tmp) throws Exception {
		// pre-#207 sidecars wrote JMH's NaN error as 0, so a legacy 0 cannot be trusted
		Path recording = recording(tmp);
		Path base = tmp.resolve("base.jfr");
		Files.copy(recording, base);
		Files.writeString(Path.of(base + ".bop"),
				"benchmark=" + ForkFixtures.BENCH + " mforks=2 tput=100.0 tputerr=0.0 tputunit=ns/op");
		String out = against(base, recording, ForkFixtures.params(2, 0), 200.0, 201.0);
		assertThat(out).contains("error band unavailable").contains("baseline").doesNotContain("SIGNIFICANT");
	}

	@Test
	void bothBandsPresentStillCallsARealChangeSignificant(@TempDir Path tmp) throws Exception {
		// look-alike: two forks each side → real error bands → a real call
		Path recording = recording(tmp);
		BenchmarkParams params = ForkFixtures.params(2, 0);
		Path base = keep(tmp, recording, params, 100.0, 101.0);
		String out = against(base, recording, params, 200.0, 201.0);
		assertThat(out).contains("SIGNIFICANT").doesNotContain("unavailable").doesNotContain("mismatch");
	}

	@Test
	void aMissingAllocationBandIsUnknownAndLeansOnNothing() {
		// the after band is missing: no call, no disagreement clause, no dispersion claim
		String v = MeasuredVerdict.allocVerdict(1_000_000, 20_000, 500_000, Double.NaN, 1, -40.0);
		assertThat(v).contains("error band unavailable on this run").contains("Δ -50.0%");
		assertThat(v).doesNotContain("SIGNIFICANT")
			.doesNotContain("NOT significant")
			.doesNotContain("disagrees")
			.doesNotContain("Dispersion")
			.doesNotContain("NaN");
		String floor = MeasuredVerdict.allocVerdict(0.002, Double.NaN, 512, 4, 2, 0.0);
		assertThat(floor).contains("new allocation").contains("on the baseline").doesNotContain("SIGNIFICANT");
		assertThat(MeasuredVerdict.dispersionNote(1_000_000, 20_000, 500_000, Double.NaN, -50.0)).isEmpty();
	}

	@Test
	void theReaderKeepsNanUnknownALegacyZeroUnknownAndANewZeroReal(@TempDir Path tmp) throws Exception {
		Path jfr = tmp.resolve("b.jfr");
		Files.writeString(Path.of(jfr + ".bop"),
				"benchmark=com.acme.B.m id=com.acme.B.m-AverageTime mforks=2 tput=1.0 tputerr=nan bop=96.0 boperr=0.0");
		MeasuredVerdict.Measured m = MeasuredVerdict.readMeasured(jfr);
		assertThat(m.tputErr).isNaN();
		assertThat(m.bopErr).isZero(); // a stored id marks a post-#207 writer: 0 is real
		assertThat(m.id).isEqualTo("com.acme.B.m-AverageTime");
		Files.writeString(Path.of(jfr + ".bop"), "benchmark=com.acme.B.m mforks=2 tput=1.0 tputerr=0 bop=96 boperr=0");
		MeasuredVerdict.Measured legacy = MeasuredVerdict.readMeasured(jfr);
		assertThat(legacy.tputErr).isNaN();
		assertThat(legacy.bopErr).isNaN();
		assertThat(legacy.id).isNull();
	}

}
