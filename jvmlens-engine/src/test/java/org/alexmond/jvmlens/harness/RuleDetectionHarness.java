package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;

import org.alexmond.jvmlens.FixHints;
import org.alexmond.jvmlens.ProfileSummary;
import org.alexmond.jvmlens.Summarizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A table-driven runner for jvmlens's own detection rules: each {@link Case} is a
 * scenario with a known pathology plus the substrings the rendered output <em>must</em>
 * contain and <em>must not</em> contain. It turns the scattered "build a summary, render,
 * assert contains" tests into one uniform form, so backfilling a fixture (mined from a
 * field-finding or the decisions log) is a single row — and a false-positive guard is
 * just a {@code mustNotContain} on a look-alike scenario. Model-synth by default (no
 * JFR); the summary comes from {@link Summaries}.
 *
 * <p>
 * Detectors that can only be exercised against a running host or a real JMH trial
 * (deadlock, agent fail-open, measured throughput/dispersion) are out of scope here by
 * design — they live in the agent / IT / jmh modules, not this in-process harness.
 */
public final class RuleDetectionHarness {

	private RuleDetectionHarness() {
	}

	/**
	 * Render {@code c} through its mode and assert its contain / not-contain
	 * expectations.
	 */
	public static void verify(Case c) {
		String out = render(c.summary(), c.mode());
		if (!c.mustContain().isEmpty()) {
			assertThat(out).as("[%s] expected to fire", c.name()).contains(c.mustContain());
		}
		for (String absent : c.mustNotContain()) {
			assertThat(out).as("[%s] must not fire: %s", c.name(), absent).doesNotContain(absent);
		}
	}

	/** Wrap a list of cases as JUnit dynamic tests (one node per fixture). */
	public static Stream<DynamicTest> asTests(List<Case> cases) {
		return cases.stream().map((c) -> DynamicTest.dynamicTest(c.name(), () -> verify(c)));
	}

	private static String render(ProfileSummary s, Mode mode) {
		return switch (mode) {
			case HINTS -> FixHints.render(s);
			case MARKDOWN -> Summarizer.render(s, Summarizer.Format.MARKDOWN);
		};
	}

	/** What to render a fixture through before asserting. */
	public enum Mode {

		/** {@code analyze --hints} — the {@link FixHints} directions block. */
		HINTS,
		/** The full markdown report. */
		MARKDOWN

	}

	/**
	 * One fixture: a named scenario, how to render it, and the substrings the output must
	 * / must not contain.
	 *
	 * @param name human label (the pathology)
	 * @param summary the model-synth scenario
	 * @param mode render path
	 * @param mustContain substrings that prove the rule fired
	 * @param mustNotContain substrings that prove it did not false-positive
	 */
	public record Case(String name, ProfileSummary summary, Mode mode, List<String> mustContain,
			List<String> mustNotContain) {
	}

}
