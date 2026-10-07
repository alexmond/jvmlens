package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessShareTest {

	@Test
	void flagsAProfileWhoseCpuRunsInsideAMockFramework() {
		// #163: Thymeleaf's own BenchmarkTest drove Mockito-mocked servlet objects
		HarnessShare harness = new HarnessShare();
		harness.count("org.mockito.", 340, 0);
		assertThat(harness.note(1000, 0)).contains("test-harness dominated")
			.contains("34% of CPU samples")
			.contains("org.mockito")
			.contains("bench --main");
	}

	@Test
	void namesAllocationWhenThatIsWhatTheHarnessDominates() {
		HarnessShare harness = new HarnessShare();
		harness.count("org.mockito.", 10, 4_400_000_000L);
		String note = harness.note(1000, 10_000_000_000L);
		assertThat(note).contains("44% of allocation").doesNotContain("CPU samples");
	}

	@Test
	void staysQuietForAnOrdinaryProfile() {
		HarnessShare harness = new HarnessShare();
		harness.count("org.mockito.", 30, 1_000L);
		assertThat(harness.note(1000, 1_000_000L)).isEmpty();
		assertThat(new HarnessShare().note(1000, 1_000_000L)).isEmpty();
	}

	@Test
	void staysQuietOnTooFewSamplesToJudge() {
		HarnessShare harness = new HarnessShare();
		harness.count("org.mockito.", 8, 0);
		assertThat(harness.note(10, 0)).isEmpty();
	}

	@Test
	void recognizesMockFrameworksButNotTheTestRunner() {
		assertThat(HarnessShare.mockLibrary(List.of("java.lang.String", "org.mockito.internal.Handler", "app.Svc")))
			.isEqualTo("org.mockito.");
		// junit frames sit under every test sample — being run by a test is not the
		// signal
		assertThat(HarnessShare.mockLibrary(List.of("app.Svc", "org.junit.platform.Launcher"))).isNull();
		// ByteBuddy alone is ambiguous: Hibernate and agents use it in production
		assertThat(HarnessShare.mockLibrary(List.of("net.bytebuddy.ByteBuddy", "app.Svc"))).isNull();
	}

}
