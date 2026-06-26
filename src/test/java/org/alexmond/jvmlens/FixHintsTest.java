package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class FixHintsTest {

	@Test
	void namesTheGotmpl4jHotSpotsFromFrameShapes() {
		// the shapes from field-finding #39: float→string formatting + per-range iterator
		// alloc
		ProfileSummary s = new ProfileSummary("r.jfr", 1000, 2, 0, 0, 0,
				List.of(new Ranked("com.acme.GoFmt.floatString", 0.50, 457,
						"com.acme.GoFmt.floatString <- jdk.internal.math.DoubleToDecimal.toString")),
				List.of(new Ranked("java.lang.Integer.formatUnsignedInt", 0.32, 638, null)),
				List.of(new Ranked("com.acme.parse.ListNode.iterator", 0.16, 143,
						"java.util.LinkedList$ListItr.<init>")),
				List.of(new Ranked("java.math.BigDecimal", 0.11, 100, null)), List.of(), List.of(), "cause",
				"com.acme");
		String md = FixHints.render(s);
		assertThat(md).contains("## Likely fix directions [possible]");
		assertThat(md).contains("number→string formatting").contains("`com.acme.GoFmt.floatString`");
		assertThat(md).contains("per-iteration iterator allocation").contains("`com.acme.parse.ListNode.iterator`");
		assertThat(md).contains("BigDecimal/BigInteger math").contains("`java.math.BigDecimal`");
		// the lever classification is the point of #53 item 2: iterator alloc is a safe
		// (structural) lever, number→string formatting is parity-sensitive (inherent)
		assertThat(md).contains("[structural] per-iteration iterator allocation");
		assertThat(md).contains("[inherent] number→string formatting");
		// structural levers are listed before inherent ones (pull the safe one first)
		assertThat(md.indexOf("[structural]")).isLessThan(md.indexOf("[inherent]"));
	}

	@Test
	void namesACapturedLambdaInAHotPathAsStructural() {
		ProfileSummary s = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0,
				List.of(new Ranked("com.acme.Svc.dispatch", 0.6, 510,
						"com.acme.Svc$$Lambda$42.apply <- java.util.concurrent.Executor")),
				List.of(), List.of(), List.of(), List.of(), List.of(), "cause", "com.acme");
		assertThat(FixHints.render(s)).contains("[structural] lambda captured per call");
	}

	@Test
	void deduplicatesAndStaysEmptyForCleanCode() {
		ProfileSummary clean = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0,
				List.of(new Ranked("com.acme.Svc.compute", 1.0, 1000, null)), List.of(), List.of(), List.of(),
				List.of(), List.of(), "cause", "com.acme");
		assertThat(FixHints.render(clean)).isEmpty();
		assertThat(FixHints.hints(clean)).isEmpty();
	}

	@Test
	void eachShapeYieldsAtMostOneHint() {
		ProfileSummary s = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0,
				List.of(new Ranked("a.StringBuilder.append", 0.4, 10, "java.lang.AbstractStringBuilder.ensureCapacity"),
						new Ranked("b.StringBuilder.toString", 0.3, 10, "java.lang.AbstractStringBuilder")),
				List.of(), List.of(), List.of(), List.of(), List.of(), "cause", "com.acme");
		// two StringBuilder rows → exactly one StringBuilder hint
		assertThat(FixHints.hints(s)).filteredOn((h) -> h.contains("StringBuilder")).hasSize(1);
	}

}
