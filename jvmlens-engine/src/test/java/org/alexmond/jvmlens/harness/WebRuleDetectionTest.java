package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.alexmond.jvmlens.harness.RuleDetectionHarness.Case;
import org.alexmond.jvmlens.harness.RuleDetectionHarness.Mode;

/**
 * P1b — the {@code web} extended section now feeds {@code --hints}. The one crisp,
 * mechanical web lever is a high error rate (validate/handle before the expensive work);
 * slow endpoints are better served by P2 correlation. Guards: a healthy endpoint fires
 * nothing, and endpoint text never trips a {@code db} or code rule.
 */
class WebRuleDetectionTest {

	private static final List<Case> CASES = List.of(
			new Case("web: high error rate → validate/handle",
					Summaries.builder()
						.web("GET /api/users/{}", 500,
								"120 reqs, avg 4.0 ms · at com.acme.UserController:37, 40 errors — high error rate")
						.build(),
					Mode.HINTS, List.of("high error rate", "GET /api/users"), List.of("N+1 query", "SELECT *")),
			new Case("web: a healthy endpoint fires nothing",
					Summaries.builder().web("GET /health", 40, "300 reqs, avg 0.1 ms").build(), Mode.HINTS, List.of(),
					List.of("high error rate")),
			new Case("isolation: endpoint text does not trip a db or code rule", Summaries.builder()
				.web("GET /reports/iterator-status", 100, "5 reqs, avg 20.0 ms · at com.acme.ReportController:9")
				.build(), Mode.HINTS, List.of(), List.of("SELECT *", "iterator allocation", "high error rate")));

	@TestFactory
	Stream<DynamicTest> webRuleFixtures() {
		return RuleDetectionHarness.asTests(CASES);
	}

}
