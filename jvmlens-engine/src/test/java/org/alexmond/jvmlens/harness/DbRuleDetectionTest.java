package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.alexmond.jvmlens.harness.RuleDetectionHarness.Case;
import org.alexmond.jvmlens.harness.RuleDetectionHarness.Mode;

/**
 * P1a — the {@code db} extended section now feeds {@code --hints}. Fixtures are the
 * pathologies field-findings kept surfacing (N+1, {@code SELECT *}), plus the guards that
 * matter for a shared rule engine: a clean query fires nothing, SQL text never trips a
 * <em>code</em> rule, and a code row still fires its own.
 */
class DbRuleDetectionTest {

	private static final List<Case> CASES = List.of(
			new Case("db: N+1 (many identical short queries) → batch",
					Summaries.builder()
						.db("select o.id from orders o where o.id = ?", 60,
								"60 calls, avg 1.0 ms — high call count, possible N+1")
						.build(),
					Mode.HINTS, List.of("N+1 query", "orders"), List.of("SELECT *")),
			new Case("db: SELECT * → project columns",
					Summaries.builder().db("select * from report where day = ?", 200, "1 calls, avg 200.0 ms").build(),
					Mode.HINTS, List.of("SELECT *", "report"), List.of("N+1 query")),
			new Case("db: a bounded, projected query fires nothing",
					Summaries.builder().db("select id from report where day = ?", 5, "3 calls, avg 2.0 ms").build(),
					Mode.HINTS, List.of(), List.of("N+1 query", "SELECT *")),
			new Case("isolation: SQL text does not trip a code rule",
					Summaries.builder()
						.db("update job_iterator set matches = ? where id = ?", 6, "2 calls, avg 3.0 ms")
						.build(),
					Mode.HINTS, List.of(), List.of("iterator allocation", "regex compiled per call")),
			new Case("isolation: a code row still fires its own rule",
					Summaries.builder().hotPath("com.acme.parse.ListNode.iterator", 0.4, 400, null).build(), Mode.HINTS,
					List.of("per-iteration iterator allocation"), List.of("N+1 query")));

	@TestFactory
	Stream<DynamicTest> dbRuleFixtures() {
		return RuleDetectionHarness.asTests(CASES);
	}

}
