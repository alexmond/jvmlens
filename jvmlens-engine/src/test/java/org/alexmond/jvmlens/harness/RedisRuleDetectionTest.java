package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.alexmond.jvmlens.harness.RuleDetectionHarness.Case;
import org.alexmond.jvmlens.harness.RuleDetectionHarness.Mode;

/**
 * P5b — the {@code redis} extended section feeds {@code --hints}. The crisp Redis lever
 * is per-key round-trips: many single-key {@code GET}s should be one {@code MGET} or a
 * pipeline. Guard: an already-batched {@code MGET} (multi-key) fires nothing.
 */
class RedisRuleDetectionTest {

	private static final List<Case> CASES = List.of(
			new Case("redis: many single-key GETs → pipeline / MGET",
					Summaries.builder()
						.redis("get", 300,
								"500 ops, avg 0.3 ms · at com.acme.SessionStore:33 "
										+ "— many single-key reads, possible N+1 round-trips")
						.build(),
					Mode.HINTS, List.of("per-key Redis round-trips", "MGET"), List.of("un-batched")),
			new Case("redis: an already-batched MGET fires nothing",
					Summaries.builder().redis("mget", 40, "60 ops, avg 0.7 ms").build(), Mode.HINTS, List.of(),
					List.of("round-trips")));

	@TestFactory
	Stream<DynamicTest> redisRuleFixtures() {
		return RuleDetectionHarness.asTests(CASES);
	}

}
