package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileDiffTest {

	private static ProfileSummary summary(long gcMs, List<Ranked> hotPaths, List<Section> sections) {
		return new ProfileSummary("rec.jfr", 1000, 1, 0, 0, gcMs, hotPaths, List.of(), List.of(), List.of(), List.of(),
				List.of(), "cause", "com.acme", sections);
	}

	@Test
	void namesImprovedRegressedNewAndGoneHotPaths() {
		ProfileSummary before = summary(76,
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.50, 457, null),
						new Ranked("com.acme.Exec.accessorFor", 0.30, 84, null),
						new Ranked("com.acme.ListNode.iterator", 0.16, 143, null)),
				List.of());
		ProfileSummary after = summary(40,
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.08, 70, null),
						new Ranked("com.acme.Exec.accessorFor", 0.30, 84, null),
						new Ranked("com.acme.Cache.get", 0.40, 350, null)),
				List.of());
		String d = ProfileDiff.diff(before, after);

		assertThat(d).startsWith("# JVM profile diff (rec.jfr → rec.jfr)");
		assertThat(d).contains("GC pause (ms): 76 → 40 (-36, -47%)");
		assertThat(d).contains("`com.acme.GoFmt.floatString` — 50%→8% (▼ 42pp)"); // improved
		assertThat(d).contains("`com.acme.Cache.get` — NEW 40%"); // appeared
		assertThat(d).contains("`com.acme.ListNode.iterator` — GONE (was 16%)"); // disappeared
		assertThat(d).doesNotContain("accessorFor"); // unchanged → dropped as noise
	}

	@Test
	void diffsExtendedSectionsByKey() {
		ProfileSummary before = summary(0, List.of(), List.of(new Section("db", "Top SQL", "ms", true,
				List.of(new Ranked("select * from line where id = ?", 0.90, 9_000_000L, null)))));
		ProfileSummary after = summary(0, List.of(), List.of(new Section("db", "Top SQL", "ms", true,
				List.of(new Ranked("select * from line where id = ?", 0.20, 2_000_000L, null)))));
		String d = ProfileDiff.diff(before, after);
		assertThat(d).contains("## db (share)");
		assertThat(d).contains("90%→20% (▼ 70pp)");
	}

	@Test
	void reportsNoChangeWhenSharesAreStable() {
		ProfileSummary s = summary(10, List.of(new Ranked("com.acme.Svc.run", 1.0, 100, null)), List.of());
		assertThat(ProfileDiff.diff(s, s)).contains("(no significant change)");
	}

}
