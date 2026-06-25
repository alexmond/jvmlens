package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.History.Sample;
import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryTest {

	private static Sample s(long t, long exec, String hot, long gcMs, long allocBytes, long oldObjects, String lock,
			long lockMs) {
		return new Sample(t, exec, hot, 0.9, exec, 1, gcMs, allocBytes, "Svc.alloc", oldObjects, lock, lockMs, "cause");
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
