package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class PerfGateTest {

	private static ProfileSummary summary(long gcMs, long oldObjects, List<Ranked> hotPaths) {
		return new ProfileSummary("r.jfr", 1000, 1, oldObjects, 0, gcMs, hotPaths, List.of(), List.of(), List.of(),
				List.of(), List.of(), "cause", "com.acme");
	}

	@Test
	void passesWhenEveryRuleHolds() {
		ProfileSummary before = summary(40, 2, List.of(new Ranked("com.acme.Svc.run", 0.50, 100, null)));
		ProfileSummary after = summary(45, 2, List.of(new Ranked("com.acme.Svc.run", 0.52, 100, null)));
		PerfGate.Result r = PerfGate.evaluate(before, after,
				"gc-ms < 200, gc-pct < 50, regression-pp < 10, new-hotpath-pp < 30, oldobj-delta < 5");
		assertThat(r.passed()).isTrue();
		assertThat(r.report()).contains("Perf gate — PASS").contains("✅");
	}

	@Test
	void failsOnGcAbsoluteCeilingAndPercentIncrease() {
		ProfileSummary before = summary(40, 0, List.of());
		ProfileSummary after = summary(1550, 0, List.of());
		assertThat(PerfGate.evaluate(before, after, "gc-ms < 200").passed()).isFalse();
		PerfGate.Result pct = PerfGate.evaluate(before, after, "gc-pct < 10");
		assertThat(pct.passed()).isFalse();
		assertThat(pct.report()).contains("Perf gate — FAIL").contains("❌");
	}

	@Test
	void failsOnHotPathRegressionAndNewHotPath() {
		ProfileSummary before = summary(0, 0, List.of(new Ranked("com.acme.A", 0.20, 10, null)));
		ProfileSummary after = summary(0, 0,
				List.of(new Ranked("com.acme.A", 0.55, 10, null), new Ranked("com.acme.B", 0.40, 10, null)));
		// A regressed +35pp
		assertThat(PerfGate.evaluate(before, after, "regression-pp < 10").passed()).isFalse();
		// B is a new hot path at 40%
		PerfGate.Result nh = PerfGate.evaluate(before, after, "new-hotpath-pp < 20");
		assertThat(nh.passed()).isFalse();
		assertThat(nh.report()).contains("NEW `com.acme.B`");
	}

	@Test
	void allocPctGatesTotalAllocationAbsolutely() {
		// #43: the absolute memory gate — immune to the share shuffle
		ProfileSummary before = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0, List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), "c", "com.acme", List.of(), 1_000_000L);
		ProfileSummary after = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "c", "com.acme", List.of(), 740_000L);
		assertThat(PerfGate.evaluate(before, after, "alloc-pct < 0").passed()).isTrue(); // -26%
																							// <
																							// 0
		assertThat(PerfGate.evaluate(after, before, "alloc-pct < 10").passed()).isFalse(); // +35%
																							// not
																							// <
																							// 10
	}

	@Test
	void failsOnRetentionGrowthAndUnrecognizedRule() {
		ProfileSummary before = summary(0, 2, List.of());
		ProfileSummary after = summary(0, 60, List.of());
		assertThat(PerfGate.evaluate(before, after, "oldobj-delta < 10").passed()).isFalse();
		assertThat(PerfGate.evaluate(before, after, "not-a-rule").passed()).isFalse();
		assertThat(PerfGate.evaluate(before, after, "gc-ms < notanumber").passed()).isFalse();
	}

}
