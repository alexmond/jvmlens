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
		// counts agree with the shares of the 1000-sample total: the gate now needs both
		ProfileSummary before = summary(0, 0, List.of(new Ranked("com.acme.A", 0.20, 200, null)));
		ProfileSummary after = summary(0, 0,
				List.of(new Ranked("com.acme.A", 0.55, 550, null), new Ranked("com.acme.B", 0.40, 400, null)));
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

	private static ProfileSummary sampled(long execSamples, List<Ranked> hotPaths) {
		return new ProfileSummary("r.jfr", execSamples, 1, 0, 0, 0, hotPaths, List.of(), List.of(), List.of(),
				List.of(), List.of(), "cause", "com.acme");
	}

	@Test
	void aPathBelowTheBaselineTopNIsNotNew() {
		// #165 cause 2: Attributes.write was in the baseline, just under the top-N. The
		// total fell, its share rose into the list — that is not a NEW hot path.
		ProfileSummary before = sampled(2000,
				List.of(new Ranked("a.A.a", 0.40, 800, null), new Ranked("a.B.b", 0.20, 400, null),
						new Ranked("a.C.c", 0.15, 300, null), new Ranked("a.D.d", 0.10, 200, null),
						new Ranked("a.E.e", 0.08, 160, null), new Ranked("a.Attributes.write", 0.025, 50, null)));
		ProfileSummary after = sampled(900, List.of(new Ranked("a.A.a", 0.50, 450, null),
				new Ranked("a.B.b", 0.30, 270, null), new Ranked("a.Attributes.write", 0.06, 54, null)));
		PerfGate.Result r = PerfGate.evaluate(before, after, "new-hotpath-pp < 5");
		assertThat(r.passed()).isTrue();
		assertThat(r.report()).contains("no new hot path");
	}

	@Test
	void aNewPathIsMeasuredAgainstTheBaselineTotalWhenTheTotalFell() {
		// #165 cause 3: genuinely new code, but 78 samples where the run shed 1190 — a 9%
		// share of a much smaller total is not a 9% regression
		ProfileSummary before = sampled(2075, List.of(new Ranked("a.Old.slow", 0.46, 963, null)));
		ProfileSummary after = sampled(885, List.of(new Ranked("a.New.fast", 0.09, 78, null)));
		PerfGate.Result r = PerfGate.evaluate(before, after, "new-hotpath-pp < 5");
		assertThat(r.passed()).isTrue();
		assertThat(r.report()).contains("NEW `a.New.fast`").contains("9% share").contains("4% of the baseline");
	}

	@Test
	void aNewPathStillFailsWhenItAddsRealWork() {
		// total flat: share and baseline-relative agree, so the gate must still fire
		ProfileSummary before = sampled(1000, List.of(new Ranked("a.A.a", 0.50, 500, null)));
		ProfileSummary after = sampled(1000,
				List.of(new Ranked("a.A.a", 0.50, 500, null), new Ranked("a.New.slow", 0.09, 90, null)));
		assertThat(PerfGate.evaluate(before, after, "new-hotpath-pp < 5").passed()).isFalse();
	}

	@Test
	void regressionNeedsARiseInAbsoluteSamplesToo() {
		// the leader shrank; A's samples fell 300 → 280 while its share rose 15% → 31%
		ProfileSummary before = sampled(2000,
				List.of(new Ranked("a.Lead.x", 0.60, 1200, null), new Ranked("a.A.a", 0.15, 300, null)));
		ProfileSummary after = sampled(900,
				List.of(new Ranked("a.A.a", 0.31, 280, null), new Ranked("a.Lead.x", 0.22, 200, null)));
		assertThat(PerfGate.evaluate(before, after, "regression-pp < 5").passed()).isTrue();
		// same shares, but A really did more work: 300 → 620 under a flat total
		ProfileSummary worse = sampled(2000,
				List.of(new Ranked("a.Lead.x", 0.60, 1200, null), new Ranked("a.A.a", 0.31, 620, null)));
		assertThat(PerfGate.evaluate(before, worse, "regression-pp < 5").passed()).isFalse();
	}

}
