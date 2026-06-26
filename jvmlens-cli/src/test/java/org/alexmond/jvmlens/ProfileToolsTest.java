package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileToolsTest {

	private static ProfileSummary sample() {
		return new ProfileSummary("rec.jfr", 1000, 2, 0, 4, 12,
				List.of(new Ranked("com.example.Svc.run", 0.75, 750, "com.example.Svc.run <- Main.main")),
				List.of(new Ranked("java.lang.Math.sqrt", 0.40, 400, null)),
				List.of(new Ranked("com.example.Svc.alloc", 0.9, 900, null)),
				List.of(new Ranked("byte[]", 0.9, 900, null)),
				List.of(new Ranked("com.example.Svc.lock", 1.0, 5, null)),
				List.of(new Ranked("com.example.Mutex", 1.0, 5, null)), "CPU-bound — `com.example.Svc.run`.",
				"com.example");
	}

	@Test
	void overviewOrientsWithoutDumpingDetail() {
		String md = ProfileTools.overview(sample());
		assertThat(md).contains("# JVM profile overview (rec.jfr)");
		assertThat(md).contains("Suspected cause: CPU-bound");
		assertThat(md).contains("hot_paths").contains("allocations").contains("lock_contention");
		// Overview orients but must not inline the ranked detail (shares, stack teasers)
		// —
		// that is what the drill tools are for.
		assertThat(md).doesNotContain("<- Main.main").doesNotContain("75%");
	}

	@Test
	void hotPathsReturnsOnlyThatSection() {
		String md = ProfileTools.hotPaths(sample());
		assertThat(md).contains("Top hot paths").contains("com.example.Svc.run");
		assertThat(md).doesNotContain("allocation").doesNotContain("Lock contention");
	}

	@Test
	void hotLeavesReturnsOnlyThatSection() {
		String md = ProfileTools.hotLeaves(sample());
		assertThat(md).contains("Hot leaf methods").contains("java.lang.Math.sqrt");
	}

	@Test
	void allocationsReturnsSitesAndTypes() {
		String md = ProfileTools.allocations(sample());
		assertThat(md).contains("Top allocation sites").contains("com.example.Svc.alloc");
		assertThat(md).contains("Top allocated types").contains("byte[]");
	}

	@Test
	void lockContentionReturnsMethodsAndMonitors() {
		String md = ProfileTools.lockContention(sample());
		assertThat(md).contains("Lock contention").contains("com.example.Svc.lock");
		assertThat(md).contains("Contended monitors").contains("com.example.Mutex");
	}

	@Test
	void extendedRendersSectionByKeyOrNone() {
		ProfileSummary withIo = new ProfileSummary("rec.jfr", 1, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "cause", "com.example", List.of(new ProfileSummary.Section("io", "External I/O",
						"ms", true, List.of(new Ranked("db:5432", 1.0, 2_000_000L, null)))));
		assertThat(ProfileTools.extended(withIo, "io")).contains("External I/O").contains("db:5432");
		assertThat(ProfileTools.extended(withIo, "pinning")).contains("(none)");
	}

}
