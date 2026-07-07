package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.alexmond.jvmlens.harness.RuleDetectionHarness.Case;
import org.alexmond.jvmlens.harness.RuleDetectionHarness.Mode;

/**
 * P5a — the {@code mongo} extended section feeds {@code --hints}. Mirrors the SQL levers
 * on documents: a repeated {@code find} is a possible N+1 document fetch (batch with
 * {@code Filters.in}), a repeated single-document {@code insertOne} is un-batched (use
 * {@code insertMany}/{@code bulkWrite}). Guard: an already-batched {@code insertMany}
 * fires nothing.
 */
class MongoRuleDetectionTest {

	private static final List<Case> CASES = List.of(
			new Case("mongo: repeated find → N+1 document fetch",
					Summaries.builder()
						.mongo("find", 620,
								"520 ops, avg 1.2 ms · at com.acme.UserRepo:44 "
										+ "— high call count, possible N+1 document fetch")
						.build(),
					Mode.HINTS, List.of("N+1 document fetch", "Filters.in"), List.of("un-batched")),
			new Case("mongo: repeated single-document insert → insertMany/bulkWrite",
					Summaries.builder()
						.mongo("insertOne", 300,
								"60 ops, avg 5.0 ms — repeated single-document write, likely un-batched")
						.build(),
					Mode.HINTS, List.of("un-batched document writes", "insertMany"), List.of("N+1 document fetch")),
			new Case("mongo: an already-batched insertMany fires nothing",
					Summaries.builder().mongo("insertMany", 100, "60 ops, avg 1.7 ms").build(), Mode.HINTS, List.of(),
					List.of("un-batched", "N+1 document fetch")));

	@TestFactory
	Stream<DynamicTest> mongoRuleFixtures() {
		return RuleDetectionHarness.asTests(CASES);
	}

}
