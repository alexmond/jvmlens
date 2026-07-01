package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.History.Sample;
import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryTest {

	private static Sample s(long t, long exec, String hot, long gcMs, long allocBytes, long oldObjects, String lock,
			long lockMs) {
		return new Sample(t, exec, hot, 0.9, exec, 1, gcMs, allocBytes, "Svc.alloc", oldObjects, lock, lockMs, "cause",
				0, 0, 0, 0);
	}

	@Test
	void sampleAndJsonLineRoundTripThroughAMapper() throws Exception {
		ProfileSummary summary = new ProfileSummary("r.jfr", 1500, 3, 7, 9, 120,
				List.of(new Ranked("com.example.Svc.run", 0.95, 1400, "stack")), List.of(),
				List.of(new Ranked("com.example.Svc.alloc", 1.0, 2_000_000, null)), List.of(),
				List.of(new Ranked("com.example.Svc.lock", 1.0, 5_000_000_000L, null)), List.of(), "CPU-bound.",
				"com.example");
		Sample built = History.sample(summary, 1234L);
		assertThat(built.t()).isEqualTo(1234L);
		assertThat(built.hot()).isEqualTo("com.example.Svc.run");
		assertThat(built.allocBytes()).isEqualTo(2_000_000L);
		assertThat(built.lockMs()).isEqualTo(5000L); // nanos -> ms
		assertThat(built.oldObjects()).isEqualTo(7L);

		String line = History.toJsonLine(summary, 1234L);
		Sample parsed = new ObjectMapper().readValue(line, Sample.class);
		assertThat(parsed).isEqualTo(built);
	}

	@Test
	void emptyRankingsYieldBlankNamesNotNulls() {
		ProfileSummary empty = new ProfileSummary("r.jfr", 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "No dominant signal.", null);
		Sample built = History.sample(empty, 1L);
		assertThat(built.hot()).isEmpty();
		assertThat(built.alloc()).isEmpty();
		assertThat(built.lock()).isEmpty();
	}

	@Test
	void digestNeedsAtLeastTwoPoints() {
		assertThat(History.digest(List.of())).contains("Need at least 2");
		assertThat(History.digest(List.of(s(1, 500, "A", 10, 100, 0, "", 0)))).contains("Need at least 2");
	}

	@Test
	void digestFlagsRetentionGrowthWhenOldObjectsAndGcRise() {
		List<Sample> run = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			long old = 2 + i * 8L; // 2 -> 66, clear growth
			long gc = 10 + i * 20L; // rising GC pressure
			run.add(s(i * 1000L, 1000, "com.example.Svc.run", gc, 1_000_000, old, "", 0));
		}
		String d = History.digest(run);
		assertThat(d).contains("possible* retention growth").contains("Stable hot path `com.example.Svc.run`");
		assertThat(d).contains("rising");
		// allocation trend is humanized, not raw bytes (1_000_000 B -> 976.6 KB)
		assertThat(d).contains("KB").doesNotContain("1000000 →");
	}

	@Test
	void digestReportsStableVsShiftingHotPathAndLockEmergence() {
		List<Sample> shifting = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String hot = (i < 3) ? "com.example.A" : "com.example.B";
			String lock = (i >= 4) ? "com.example.Mutex" : "";
			long lockMs = (i >= 4) ? 4000 : 0;
			shifting.add(s(i * 1000L, 1000, hot, 5, 100, 1, lock, lockMs));
		}
		String d = History.digest(shifting);
		assertThat(d).contains("Hot path shifted").contains("latest `com.example.B`");
		assertThat(d).contains("Contention in 2/6 windows").contains("`com.example.Mutex`");
		assertThat(d).contains("No sustained retention growth");
	}

	@Test
	void digestWarnsOnLowSampleVolumeAndNoLocks() {
		List<Sample> run = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			run.add(s(i * 1000L, 8, "", 0, 0, 0, "", 0)); // 8 exec samples, no hot path,
															// no locks
		}
		String d = History.digest(run);
		assertThat(d).contains("⚠ Avg").contains("exec samples/window");
		assertThat(d).contains("No application hot path");
		assertThat(d).contains("No lock contention measured");
	}

	@Test
	void sampleCapturesIoAndPinningFromSections() {
		ProfileSummary s = new ProfileSummary("r.jfr", 100, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "cause", "com.example",
				List.of(new ProfileSummary.Section("io", "External I/O", "ms", true,
						List.of(new Ranked("db-host:5432", 1.0, 4_000_000_000L, "2 MB"))),
						new ProfileSummary.Section("pinning", "VT pinning", "ms", true,
								List.of(new Ranked("com.example.Svc.run", 1.0, 1_500_000_000L, "MONITOR")))));
		Sample built = History.sample(s, 1L);
		assertThat(built.ioMs()).isEqualTo(4000L);
		assertThat(built.pinnedMs()).isEqualTo(1500L);
	}

	@Test
	void digestReportsIoAndPinningTrends() {
		List<Sample> run = new java.util.ArrayList<>();
		for (int i = 0; i < 6; i++) {
			Sample base = s(i * 1000L, 1000, "com.example.Svc.run", 5, 100, 0, "", 0);
			run.add(new Sample(base.t(), base.exec(), base.hot(), base.hotShare(), base.hotCount(), base.gcPauses(),
					base.gcMs(), base.allocBytes(), base.alloc(), base.oldObjects(), base.lock(), base.lockMs(),
					base.cause(), 10L + i * 30L, 5L + i * 10L, 0, 0));
		}
		String d = History.digest(run);
		assertThat(d).contains("External I/O blocked time/window rising");
		assertThat(d).contains("Virtual-thread pinning time/window rising");
	}

	@Test
	void digestReportsWebAndDbTrendsWhenPresent() {
		List<Sample> run = new java.util.ArrayList<>();
		for (int i = 0; i < 6; i++) {
			Sample b = s(i * 1000L, 1000, "com.example.Svc.run", 5, 100, 0, "", 0);
			run.add(new Sample(b.t(), b.exec(), b.hot(), b.hotShare(), b.hotCount(), b.gcPauses(), b.gcMs(),
					b.allocBytes(), b.alloc(), b.oldObjects(), b.lock(), b.lockMs(), b.cause(), 0, 0, 20L + i * 40L,
					100L + i * 50L));
		}
		String d = History.digest(run);
		assertThat(d).contains("## Application (web / db) [measured]");
		assertThat(d).contains("HTTP time/window rising");
		assertThat(d).contains("SQL time/window rising");
	}

	@Test
	void digestSegmentsAtARestartGapAndExcludesTheStartupBurst() {
		// #129: a multi-day monitor spans several redeploys. Each lifetime opens with a
		// cold-start burst (5–10× exec/gc/alloc). Without restart awareness the burst
		// skews
		// the whole-window trend to "rising". Here: 6 steady windows (300s interval), a
		// restart gap (5× interval), a startup burst, then 5 more steady windows.
		long dt = 300_000L;
		List<Sample> run = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			run.add(s(i * dt, 150, "app.Svc.handle", 180, 60_000_000L, 10, "", 0));
		}
		long gapStart = 6 * dt + 5 * dt; // a hole 5× the interval → a restart boundary
		run.add(s(gapStart, 1629, "app.UnitrackApplication.main", 3674, 967_000_000L, 0, "", 0)); // burst
		for (int i = 1; i <= 5; i++) {
			run.add(s(gapStart + i * dt, 150, "app.Svc.handle", 180, 60_000_000L, 10, "", 0));
		}
		String d = History.digest(run);

		// the restart is called out and the burst window is excluded
		assertThat(d).contains("## Lifecycle").contains("1 restart detected").contains("sample(s) dropped");
		// with the startup frame excluded, the steady app frame is the stable hot path —
		// not the freshly-restarted pod's `main`
		assertThat(d).contains("Stable hot path `app.Svc.handle`").doesNotContain("UnitrackApplication.main");
		// the steady allocation trend is flat at ~60 MB, not "rising" toward the 967 MB
		// burst
		assertThat(d).contains("Allocation (top-site bytes) flat").doesNotContain("967");
	}

	@Test
	void digestWithoutARestartGapAddsNoLifecycleSection() {
		List<Sample> run = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			run.add(s(i * 300_000L, 150, "app.Svc.handle", 180, 60_000_000L, 10, "", 0));
		}
		assertThat(History.digest(run)).doesNotContain("## Lifecycle").doesNotContain("restart detected");
	}

	@Test
	void jsonArrayRendersAllSamples() {
		String arr = History.toJsonArray(List.of(s(1, 100, "A", 1, 1, 1, "", 0), s(2, 200, "B", 2, 2, 2, "", 0)));
		assertThat(arr).startsWith("[").contains("\"hot\":\"A\"").contains("\"hot\":\"B\"").endsWith("]\n");
		assertThat(History.toJsonArray(List.of())).isEqualTo("[]\n");
	}

	@Test
	void jsonEscapesSpecialCharactersInNames() {
		String line = History.toJson(s(1, 10, "a\"b\\c\nd", 0, 0, 0, "", 0));
		assertThat(line).contains("\\\"").contains("\\\\").contains("\\n");
	}

}
