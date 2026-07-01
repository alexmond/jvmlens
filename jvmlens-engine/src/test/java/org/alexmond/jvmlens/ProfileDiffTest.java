package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileDiffTest {

	private static ProfileSummary summary(long gcMs, long allocBytes, List<Ranked> hotPaths, List<Ranked> allocSites) {
		return new ProfileSummary("rec.jfr", 1000, 1, 0, 0, gcMs, hotPaths, List.of(), allocSites, List.of(), List.of(),
				List.of(), "cause", "com.acme", List.of(), allocBytes);
	}

	private static ProfileSummary withSamples(long execSamples, List<Ranked> hotPaths) {
		return new ProfileSummary("rec.jfr", execSamples, 1, 0, 0, 0, hotPaths, List.of(), List.of(), List.of(),
				List.of(), List.of(), "cause", "com.acme", List.of(), 0);
	}

	@Test
	void anchorsOnAbsoluteSoAFallingSiteIsNotLabelledRising() {
		// #43: floatString's absolute bytes FELL but its share ROSE (the total shrank).
		// Share-only diffing would call this a regression; absolute must show it
		// improved.
		ProfileSummary before = summary(76, 1_394_849, List.of(),
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.22, 530_000, null),
						new Ranked("com.acme.Executor.printText", 0.50, 1_200_000, null),
						new Ranked("com.acme.GoTemplate.render", 0.26, 620_000, null)));
		ProfileSummary after = summary(58, 1_038_586, List.of(),
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.51, 307_000, null),
						new Ranked("com.acme.Executor.printText", 0.06, 36_000, null)));
		String d = ProfileDiff.diff(before, after);

		// the missing denominator is now in Totals, and it shows the reduction
		assertThat(d).contains("- Allocation: 1.3 MB → 1014.2 KB").contains("-26%)");
		assertThat(d).contains("- GC pause: 76 ms → 58 ms");
		// floatString: absolute DOWN (▼) even though its share rose 22%→51%
		assertThat(d).contains("`com.acme.GoFmt.floatString` — 517.6 KB → 299.8 KB (▼ 42%) [share 22%→51%]");
		assertThat(d).contains("`com.acme.GoTemplate.render` — GONE");
	}

	@Test
	void diffsHotPathsByAbsoluteWithNewAndGone() {
		ProfileSummary before = summary(0, 0,
				List.of(new Ranked("com.acme.A.run", 0.50, 500, null), new Ranked("com.acme.B.iter", 0.16, 160, null)),
				List.of());
		ProfileSummary after = summary(0, 0,
				List.of(new Ranked("com.acme.A.run", 0.10, 100, null), new Ranked("com.acme.C.fmt", 0.40, 400, null)),
				List.of());
		String d = ProfileDiff.diff(before, after);
		assertThat(d).contains("## Hot paths");
		assertThat(d).contains("`com.acme.A.run` — 500 → 100 (▼ 80%) [share 50%→10%]");
		assertThat(d).contains("`com.acme.C.fmt` — NEW 400 (40% share)");
		assertThat(d).contains("`com.acme.B.iter` — GONE (was 160, 16%)");
	}

	@Test
	void flagsAllocSiteRisingAgainstAFallingTotalAsSamplingRedistribution() {
		// #52: total allocation fell, but a stable cached site's *attributed* bytes
		// ballooned — JFR sampling reattributing weight as the dominant allocator shrank,
		// not a real per-site regression. Annotate (hedge), don't suppress.
		ProfileSummary before = summary(0, 9_000_000_000L, List.of(),
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.72, 7_000_000_000L, null),
						new Ranked("com.acme.Executor.accessorFor", 0.05, 510_000_000L, null)));
		ProfileSummary after = summary(0, 8_300_000_000L, List.of(),
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.55, 5_000_000_000L, null),
						new Ranked("com.acme.Executor.accessorFor", 0.22, 2_000_000_000L, null)));
		String d = ProfileDiff.diff(before, after);

		String accLine = line(d, "accessorFor");
		String floatLine = line(d, "floatString");
		// the site that ROSE while the total FELL is hedged
		assertThat(accLine).contains("▲").contains("(possible sampling redistribution — total alloc fell 8%)");
		// the site moving WITH the total (it fell too) is a real win — no hedge
		assertThat(floatLine).contains("▼").doesNotContain("redistribution");
	}

	@Test
	void flagsHotPathRisingAgainstAFallingSampleTotalAsRedistribution() {
		// #110 finding 2: total exec samples fell, but a method's absolute samples rose —
		// its
		// share rises mostly because the rest of the profile shrank, not because it got
		// slower. Hedge the CPU ▲ row like the alloc one (#43/#44); annotate, never
		// suppress.
		ProfileSummary before = withSamples(1000, List.of(new Ranked("com.acme.GoFmt.floatString", 0.20, 200, null),
				new Ranked("com.acme.Executor.printText", 0.50, 500, null)));
		ProfileSummary after = withSamples(800, List.of(new Ranked("com.acme.GoFmt.floatString", 0.30, 240, null),
				new Ranked("com.acme.Executor.printText", 0.10, 80, null)));
		String d = ProfileDiff.diff(before, after);

		// floatString rose in absolute while the sample total fell → hedged
		assertThat(line(d, "floatString")).contains("▲")
			.contains("(possible sampling redistribution — total samples fell 20%)");
		// printText fell with the total → a real drop, no hedge
		assertThat(line(d, "printText")).contains("▼").doesNotContain("redistribution");
	}

	@Test
	void hedgesAHotPathRiseUnderAFlatExecSampleTotalAsRedistribution() {
		// #122: total exec samples ~flat (1572→1551); parseWithCache fell,
		// renderChartTemplates
		// rose — the rise is a larger share of a conserved total, not more work.
		ProfileSummary before = withSamples(1572, List.of(new Ranked("o.j.Engine.parseWithCache", 0.42, 658, null),
				new Ranked("o.j.Engine.renderChartTemplates", 0.17, 264, null)));
		ProfileSummary after = withSamples(1551, List.of(new Ranked("o.j.Engine.parseWithCache", 0.31, 474, null),
				new Ranked("o.j.Engine.renderChartTemplates", 0.22, 338, null)));
		String d = ProfileDiff.diff(before, after);

		// the ▲ frame under a flat total is hedged as redistribution, not read as a
		// regression
		assertThat(line(d, "renderChartTemplates")).contains("▲").contains("total samples ~flat");
		// the section caveat about per-op vs throughput fires (points at `bench`)
		assertThat(d).contains("Total exec samples are ~flat").contains("bench");
		// the ▼ frame (a real reduction) is not hedged
		assertThat(line(d, "parseWithCache")).contains("▼").doesNotContain("redistribution");
	}

	@Test
	void hedgesAHotPathThatOutpacedAModestlyRisingTotalAsRedistribution() {
		// #127: a fixed-duration web-render capture — total exec samples rose only 13%
		// (901→1020) after an N+1 removal, but freed frames rose far more (renderLoop
		// 227→364, share 25%→36%) because the faster loop did more renders in the fixed
		// window. Neither the opposing-row hedge (#110, needs a falling row) nor the
		// flat-total caveat (#122, needs a ~flat total) fires — so this rise read as a
		// regression. Hedge the disproportionate mover and print the throughput caveat.
		ProfileSummary before = withSamples(901,
				List.of(new Ranked("o.j.ProfilingRenderTest.renderLoop", 0.25, 227, null),
						new Ranked("o.j.BranchService.summarize", 0.30, 270, null)));
		ProfileSummary after = withSamples(1020,
				List.of(new Ranked("o.j.ProfilingRenderTest.renderLoop", 0.36, 364, null),
						new Ranked("o.j.FlakyTestService.listFlaky", 0.14, 143, null)));
		String d = ProfileDiff.diff(before, after);

		// the frame that outpaced the +13% total (share 25%→36%) is hedged, not read as a
		// regression
		assertThat(line(d, "renderLoop")).contains("▲").contains("outpaced the +13% total");
		// the section caveat points at a fixed-iteration `bench` for the per-op number
		assertThat(d).contains("Hot-path shares shifted more than the +13% exec-sample total").contains("bench");
	}

	@Test
	void doesNotHedgeAFrameThatTrackedTheRisingTotal() {
		// A uniform slowdown: total and the frame both rose ~13% (share ~flat) — a real
		// regression, not redistribution. No per-row hedge, no throughput caveat.
		ProfileSummary before = withSamples(1000,
				List.of(new Ranked("o.j.Svc.run", 0.50, 500, null), new Ranked("o.j.Svc.other", 0.50, 500, null)));
		ProfileSummary after = withSamples(1130,
				List.of(new Ranked("o.j.Svc.run", 0.50, 565, null), new Ranked("o.j.Svc.other", 0.50, 565, null)));
		String d = ProfileDiff.diff(before, after);

		assertThat(line(d, "Svc.run")).contains("▲").doesNotContain("redistribution");
		assertThat(d).doesNotContain("shares shifted more than");
	}

	private static String line(String diff, String needle) {
		return List.of(diff.split("\n")).stream().filter((l) -> l.contains(needle)).findFirst().orElse("");
	}

	@Test
	void dropsNegligibleChange() {
		ProfileSummary s = summary(10, 1000, List.of(new Ranked("com.acme.Svc.run", 1.0, 100, null)), List.of());
		assertThat(ProfileDiff.diff(s, s)).contains("## Hot paths\n- (no significant change)");
	}

	@Test
	void diffsExtendedSectionsByAbsoluteMs() {
		Section dbBefore = new Section("db", "Top SQL", "ms", true,
				List.of(new Ranked("select 1", 0.9, 9_000_000_000L, null)));
		Section dbAfter = new Section("db", "Top SQL", "ms", true,
				List.of(new Ranked("select 1", 0.9, 2_000_000_000L, null)));
		ProfileSummary before = new ProfileSummary("r.jfr", 1, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "c", "com.acme", List.of(dbBefore), 0);
		ProfileSummary after = new ProfileSummary("r.jfr", 1, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "c", "com.acme", List.of(dbAfter), 0);
		assertThat(ProfileDiff.diff(before, after)).contains("## db").contains("9000 ms → 2000 ms (▼ 78%)");
	}

	@Test
	void hedgesPerSiteDeltasWhenAllocationSamplesAreFew() {
		// #50 item 3: a short trial → few alloc samples → per-site byte deltas are noisy.
		ProfileSummary before = new ProfileSummary("a.jfr", 1000, 1, 0, 0, 0, List.of(), List.of(),
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 1_000_000, null)), List.of(), List.of(), List.of(), "c",
				"com.acme", List.of(), 1_000_000L, 40L);
		ProfileSummary after = new ProfileSummary("b.jfr", 1000, 1, 0, 0, 0, List.of(), List.of(),
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 600_000, null)), List.of(), List.of(), List.of(), "c",
				"com.acme", List.of(), 600_000L, 35L);
		assertThat(ProfileDiff.diff(before, after)).contains("Low allocation samples (before 40 / after 35)");
	}

	@Test
	void rollsUpExtractedHelperAllocationByType() {
		// #99: an extract-method fix splits one drop across caller ▼ + a NEW callee in
		// the
		// same class; the by-type rollup shows the net so it doesn't read as a
		// regression.
		ProfileSummary before = summary(0, 7_600_000_000L, List.of(),
				List.of(new Ranked("com.acme.GoFmt.floatString", 1.0, 7_600_000_000L, null)));
		ProfileSummary after = summary(0, 5_800_000_000L, List.of(),
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.62, 3_600_000_000L, null),
						new Ranked("com.acme.GoFmt.renderDigits", 0.38, 2_200_000_000L, null)));
		assertThat(ProfileDiff.diff(before, after)).contains("Allocation by type (rollup")
			.contains("`com.acme.GoFmt.*`")
			.contains("▼ 24%")
			.contains("[2 methods]");
	}

	@Test
	void noTypeRollupForSingleMethodClasses() {
		// each class has one alloc method → no rollup (the per-site rows already
		// suffice).
		ProfileSummary before = summary(0, 2_000_000, List.of(), List
			.of(new Ranked("com.acme.A.x", 0.5, 1_000_000, null), new Ranked("com.acme.B.y", 0.5, 1_000_000, null)));
		ProfileSummary after = summary(0, 1_000_000, List.of(),
				List.of(new Ranked("com.acme.A.x", 1.0, 1_000_000, null)));
		assertThat(ProfileDiff.diff(before, after)).doesNotContain("Allocation by type (rollup");
	}

	@Test
	void cautionsOnASmallSampledAllocationDelta() {
		// #104: a sub-15% allocation-total delta from a single recording can be sampling
		// noise.
		ProfileSummary before = summary(0, 10_000_000_000L, List.of(),
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 10_000_000_000L, null)));
		ProfileSummary after = summary(0, 9_100_000_000L, List.of(), // -9% → in the noise
																		// band
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 9_100_000_000L, null)));
		assertThat(ProfileDiff.diff(before, after)).contains("sampled").contains("within sampling noise");
	}

	@Test
	void noSampledNoiseNoteForALargeAllocationDrop() {
		ProfileSummary before = summary(0, 10_000_000_000L, List.of(),
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 10_000_000_000L, null)));
		ProfileSummary after = summary(0, 5_000_000_000L, List.of(), // -50% → clearly
																		// real
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 5_000_000_000L, null)));
		assertThat(ProfileDiff.diff(before, after)).doesNotContain("within sampling noise");
	}

	@Test
	void noLowAllocNoteWhenSamplesUntracked() {
		// the 16-arg ctor leaves allocSamples at 0 → no false "low samples" alarm.
		ProfileSummary before = summary(0, 1_000_000, List.of(),
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 1_000_000, null)));
		ProfileSummary after = summary(0, 600_000, List.of(),
				List.of(new Ranked("com.acme.Svc.alloc", 1.0, 600_000, null)));
		assertThat(ProfileDiff.diff(before, after)).doesNotContain("Low allocation samples");
	}

}
