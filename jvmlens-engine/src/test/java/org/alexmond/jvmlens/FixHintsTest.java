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
	void namesAnUncachedReflectiveLookupFromItsLeaves() {
		// #162: OGNL resolved a record accessor with Class.getMethods() on every read —
		// the lever is the lookup (memoize it), not the invoke
		ProfileSummary s = new ProfileSummary("r.jfr", 2075, 1, 0, 0, 0,
				List.of(new Ranked("org.thymeleaf.OGNLVariableExpressionEvaluator.executeExpression", 0.46, 963,
						"java.lang.StringLatin1.toLowerCase:423 416/963 · java.lang.Class.copyMethods 114/963")),
				List.of(), List.of(), List.of(), List.of(), List.of(), "cause", "org.thymeleaf");
		assertThat(FixHints.render(s)).contains("[structural] reflective member lookup per call").contains("memoize");
	}

	@Test
	void aReflectiveInvokeAloneIsNotALookupHint() {
		ProfileSummary s = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0,
				List.of(new Ranked("com.acme.Svc.call", 0.6, 600, "java.lang.reflect.Method.invoke 500/600")),
				List.of(), List.of(), List.of(), List.of(), List.of(), "cause", "com.acme");
		assertThat(FixHints.render(s)).contains("reflective dispatch").doesNotContain("reflective member lookup");
	}

	private static ProfileSummary hotPath(String name, String teaser) {
		return new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0, List.of(new Ranked(name, 0.5, 500, teaser)), List.of(),
				List.of(), List.of(), List.of(), List.of(), "cause", "com.acme");
	}

	@Test
	void namesLoggingInAHotPath() {
		String md = FixHints.render(hotPath("com.acme.OrderService.price",
				"ch.qos.logback.classic.Logger.buildLoggingEventAndAppend:426 210/500 · java.lang.String.format 90/500"));
		assertThat(md).contains("[structural] logging in a hot path").contains("`com.acme.OrderService.price`");
		// log4j2 and java.util.logging read the same way
		assertThat(FixHints.render(hotPath("com.acme.A.a", "org.apache.logging.log4j.core.Logger.log 200/500")))
			.contains("logging in a hot path");
		assertThat(FixHints.render(hotPath("com.acme.A.a", "java.util.logging.Logger.doLog 200/500")))
			.contains("logging in a hot path");
	}

	@Test
	void aUserClassThatOnlyMentionsLoggingIsNotALoggingHint() {
		assertThat(FixHints.render(hotPath("com.acme.logging.AuditTrail.record", "java.util.HashMap.put 300/500")))
			.doesNotContain("logging in a hot path");
		// SLF4J's API alone is a thin facade — the cost shows in the backend's frames
		assertThat(FixHints.render(hotPath("com.acme.A.a", "org.slf4j.LoggerFactory.getLogger 5/500")))
			.doesNotContain("logging in a hot path");
	}

	@Test
	void namesAJacksonMapperBuiltPerCall() {
		// serializer/deserializer construction in the hot path = a mapper that is not
		// reused
		String md = FixHints.render(hotPath("com.acme.Api.toJson",
				"com.fasterxml.jackson.databind.ser.BeanSerializerFactory.constructBeanOrAddOnSerializer 180/500"));
		assertThat(md).contains("[structural] Jackson is building (de)serializers").contains("ObjectMapper");
		// Jackson 3 moved to the tools.jackson package
		assertThat(FixHints.render(hotPath("com.acme.Api.fromJson",
				"tools.jackson.databind.deser.DeserializerCache._createAndCache2 140/500")))
			.contains("Jackson is building (de)serializers");
		assertThat(FixHints
			.render(hotPath("com.acme.Api.toJson", "com.fasterxml.jackson.databind.ObjectMapper.<init> 120/500")))
			.contains("Jackson is building (de)serializers");
	}

	@Test
	void ordinaryJacksonSerializationIsNotAMapperHint() {
		// a reused mapper doing its job: writing fields is inherent work, not a misuse
		assertThat(FixHints.render(hotPath("com.acme.Api.toJson",
				"com.fasterxml.jackson.databind.ser.BeanSerializer.serialize 300/500 · "
						+ "com.fasterxml.jackson.core.json.UTF8JsonGenerator.writeString 90/500")))
			.doesNotContain("Jackson is building");
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
	void namesPerCallRegexCompilationFromAReplaceAllSourceLine() {
		// #119: String.replaceAll(String,…) silently recompiles the regex each call —
		// detect it
		// from the (--source) line and name the hoist-to-static-final fix, with the call
		// site.
		ProfileSummary s = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0, List.of(), List.of(),
				List.of(new Ranked("com.acme.PrintfFunction.lambda$printf$0", 1.0, 1000,
						":44 · Pattern 1.0 GB ⟶ format = format.replaceAll(\"(?<!%)%([gG])\", \"%s\")")),
				List.of(), List.of(), List.of(), "cause", "com.acme");
		String md = FixHints.render(s);
		assertThat(md).contains("[structural] regex compiled per call")
			.contains("static final")
			.contains("`com.acme.PrintfFunction.lambda$printf$0`");
	}

	@Test
	void namesPerCallRegexCompilationFromPatternLeaves() {
		// #119: the regex-compile internals as hot leaves are the other tell of a
		// per-call compile.
		ProfileSummary s = new ProfileSummary("r.jfr", 1000, 1, 0, 0, 0, List.of(),
				List.of(new Ranked("java.util.regex.Pattern.compile", 0.5, 500, null)), List.of(), List.of(), List.of(),
				List.of(), "cause", "com.acme");
		assertThat(FixHints.render(s)).contains("[structural] regex compiled per call");
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
