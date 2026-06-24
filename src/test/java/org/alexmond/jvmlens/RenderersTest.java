package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class RenderersTest {

	private static ProfileSummary sample() {
		return new ProfileSummary("rec.jfr", 100, 2, 0, 4, 12,
				List.of(new Ranked("com.example.Svc.run", 0.75, 750, "com.example.Svc.run <- Main.main")),
				List.of(new Ranked("java.lang.Math.sqrt", 0.40, 400, null)), List.of(), List.of(),
				List.of(new Ranked("com.example.Svc.lock", 1.0, 1, null)),
				List.of(new Ranked("com.example.Mutex", 1.0, 1, null)), "CPU-bound — `com.example.Svc.run`.",
				"com.example");
	}

	@Test
	void markdownRendersSectionsAndNoneForEmpty() {
		String md = Renderers.markdown(sample());
		assertThat(md).contains("# JVM profile summary (rec.jfr)");
		assertThat(md).contains("- `com.example.Svc.run` — 75% (750 samples)  (com.example.Svc.run <- Main.main)");
		assertThat(md).contains("Contended monitors");
		assertThat(md).contains("- (none)"); // both allocation sections are empty
		assertThat(md).contains("## Suspected cause (heuristic)");
	}

	@Test
	void surfacesDetectedAppPackageInMarkdownAndJson() {
		assertThat(Renderers.markdown(sample())).contains("Application code under `com.example.*`.");
		assertThat(Renderers.json(sample())).contains("\"appPackage\": \"com.example\"");
	}

	@Test
	void showsPerRowHitCounts() {
		// Hit count is the "other side" of adequacy: a high share on few samples is
		// noise.
		assertThat(Renderers.markdown(sample())).contains("— 75% (750 samples)").contains("— 40% (400 samples)");
		assertThat(Renderers.json(sample())).contains("\"share\": 0.7500, \"count\": 750");
	}

	@Test
	void reportFocusIncludesOnlyTheRelevantSections() {
		String cpu = Renderers.report(sample(), Summarizer.Report.CPU);
		assertThat(cpu).contains("Top hot paths").doesNotContain("allocation sites").doesNotContain("Lock contention");

		String mem = Renderers.report(sample(), Summarizer.Report.MEMORY);
		assertThat(mem).contains("Top allocation sites")
			.doesNotContain("Top hot paths")
			.doesNotContain("Lock contention");

		String locks = Renderers.report(sample(), Summarizer.Report.LOCKS);
		assertThat(locks).contains("Lock contention")
			.doesNotContain("Top hot paths")
			.doesNotContain("allocation sites");

		// Every focused report still carries the header and the heuristic cause.
		assertThat(cpu).contains("# JVM profile summary").contains("Suspected cause");
	}

	@Test
	void labelsSampledVsMeasuredSections() {
		String md = Renderers.markdown(sample());
		assertThat(md).contains("Top hot paths (application code, by sample share) [sampled]");
		assertThat(md).contains("Lock contention (blocked time, by application method) [measured]");
	}

	@Test
	void markdownWarnsOnLowSampleCount() {
		// sample() has 100 exec samples, below the 200 adequacy threshold.
		assertThat(Renderers.markdown(sample())).contains("⚠ Only 100 execution samples");
	}

	@Test
	void markdownWarnsWhenNoExecutionSamples() {
		ProfileSummary s = new ProfileSummary("r.jfr", 0, 1, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "No dominant signal.", null);
		assertThat(Renderers.markdown(s)).contains("No execution samples");
	}

	@Test
	void markdownHasNoCaveatWhenSamplesAdequate() {
		ProfileSummary s = new ProfileSummary("r.jfr", 5000, 1, 0, 0, 0,
				List.of(new Ranked("com.example.Svc.run", 1.0, 5000, null)), List.of(), List.of(), List.of(), List.of(),
				List.of(), "CPU-bound.", "com.example");
		assertThat(Renderers.markdown(s)).doesNotContain("⚠");
	}

	@Test
	void markdownOmitsMonitorSectionWhenEmpty() {
		ProfileSummary s = new ProfileSummary("r.jfr", 1, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "No dominant signal.", null);
		assertThat(Renderers.markdown(s)).doesNotContain("Contended monitors");
	}

	@Test
	void jsonIsScopedWithEmptyArraysAndShares() {
		String json = Renderers.json(sample());
		assertThat(json).startsWith("{\n  \"source\": \"rec.jfr\"");
		assertThat(json).contains("\"gcPauseMillis\": 12");
		assertThat(json).contains("\"name\": \"com.example.Svc.run\", \"share\": 0.7500");
		assertThat(json).contains("\"allocSites\": [],");
		assertThat(json).contains("\"stack\": null");
		assertThat(json).endsWith("}\n");
	}

	@Test
	void jsonEscapesSpecialCharacters() {
		String nasty = "a\"b\\c\nd\tef" + (char) 1;
		ProfileSummary s = new ProfileSummary(nasty, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "ok", null);
		String json = Renderers.json(s);
		assertThat(json).contains("\\\"").contains("\\\\").contains("\\n").contains("\\t").contains("\\u0001");
	}

	@Test
	void promptPrependsInstructionToMarkdown() {
		String prompt = Renderers.prompt(sample());
		assertThat(prompt).startsWith("You are a JVM performance expert.");
		assertThat(prompt).contains("---\n\n# JVM profile summary");
	}

}
