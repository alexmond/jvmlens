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

	@Test
	void recognizesATestJvmFromItsLaunchCommand() {
		assertThat(HarnessShare.testLauncher("/repo/target/surefire/surefirebooter-2026.jar /repo/target/surefire"))
			.isEqualTo("Maven Surefire");
		assertThat(HarnessShare
			.testLauncher("worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 3'"))
			.isEqualTo("Gradle test worker");
		assertThat(HarnessShare.testLauncher("com.intellij.rt.junit.JUnitStarter -ideVersion5 -junit5 a.FooTest"))
			.isEqualTo("IDE JUnit runner");
		assertThat(HarnessShare.testLauncher("org.junit.platform.console.ConsoleLauncher --scan-classpath"))
			.isEqualTo("JUnit console launcher");
		assertThat(HarnessShare.testLauncher("org.testng.TestNG suite.xml")).isEqualTo("TestNG");
	}

	@Test
	void anOrdinaryLaunchCommandIsNotATestJvm() {
		assertThat(HarnessShare.testLauncher("/opt/app/app.jar --server.port=8080")).isNull();
		// a JMH fork is a benchmark, not a test run
		assertThat(HarnessShare.testLauncher("org.openjdk.jmh.runner.ForkedMain 127.0.0.1 40123")).isNull();
		// a Gradle worker that is not a test executor (e.g. a compiler daemon)
		assertThat(HarnessShare.testLauncher("worker.org.gradle.process.internal.worker.GradleWorkerMain 'Worker 2'"))
			.isNull();
		assertThat(HarnessShare.testLauncher(null)).isNull();
	}

	@Test
	void notesARecordingTakenFromATestJvm() {
		HarnessShare harness = new HarnessShare();
		harness.launchedBy("/repo/target/surefire/surefirebooter-2026.jar");
		assertThat(harness.note(1000, 0)).contains("Recorded from a test run (Maven Surefire)")
			.contains("fixture")
			.doesNotContain("dominated");
	}

	@Test
	void notesATestRunFromRunnerFramesWhenTheLaunchCommandIsUnknown() {
		HarnessShare harness = new HarnessShare();
		harness.countRunner("org.junit.", 700);
		assertThat(harness.note(1000, 0)).contains("Recorded from a test run (org.junit)");
		// a few runner frames (one test among much else) is not the signal
		HarnessShare few = new HarnessShare();
		few.countRunner("org.junit.", 100);
		assertThat(few.note(1000, 0)).isEmpty();
	}

	@Test
	void aMockDominatedTestRunCarriesBothNotes() {
		HarnessShare harness = new HarnessShare();
		harness.launchedBy("/repo/target/surefire/surefirebooter-2026.jar");
		harness.count("org.mockito.", 340, 0);
		assertThat(harness.note(1000, 0)).contains("Recorded from a test run (Maven Surefire)")
			.contains("test-harness dominated");
	}

	@Test
	void recognizesTestRunnerFrames() {
		assertThat(HarnessShare.runnerLibrary(List.of("app.Svc", "org.junit.platform.launcher.core.DefaultLauncher")))
			.isEqualTo("org.junit.");
		assertThat(HarnessShare.runnerLibrary(List.of("app.Svc", "org.testng.TestRunner"))).isEqualTo("org.testng.");
		assertThat(HarnessShare.runnerLibrary(List.of("app.Svc", "java.lang.Thread"))).isNull();
	}

}
