package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class RenderersTest {

	private static ProfileSummary sample() {
		return new ProfileSummary("rec.jfr", 100, 2, 0, 4, 12,
				List.of(new Ranked("com.example.Svc.run", 0.75, "com.example.Svc.run <- Main.main")),
				List.of(new Ranked("java.lang.Math.sqrt", 0.40, null)), List.of(), List.of(),
				List.of(new Ranked("com.example.Svc.lock", 1.0, null)),
				List.of(new Ranked("com.example.Mutex", 1.0, null)), "CPU-bound — `com.example.Svc.run`.");
	}

	@Test
	void markdownRendersSectionsAndNoneForEmpty() {
		String md = Renderers.markdown(sample());
		assertThat(md).contains("# JVM profile summary (rec.jfr)");
		assertThat(md).contains("- `com.example.Svc.run` — 75%  (com.example.Svc.run <- Main.main)");
		assertThat(md).contains("Contended monitors");
		assertThat(md).contains("- (none)"); // both allocation sections are empty
		assertThat(md).contains("## Suspected cause (heuristic)");
	}

	@Test
	void markdownOmitsMonitorSectionWhenEmpty() {
		ProfileSummary s = new ProfileSummary("r.jfr", 1, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), "No dominant signal.");
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
				List.of(), List.of(), "ok");
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
