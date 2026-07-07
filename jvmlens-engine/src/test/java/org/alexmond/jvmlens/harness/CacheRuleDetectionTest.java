package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.alexmond.jvmlens.harness.RuleDetectionHarness.Case;
import org.alexmond.jvmlens.harness.RuleDetectionHarness.Mode;

/**
 * P1c — the {@code cache} extended section now feeds {@code --hints}. The crisp cache
 * lever is a low hit rate (the cache isn't paying for itself). Guards: a healthy cache
 * fires nothing, and cache-op text never trips a {@code db}/{@code web}/code rule.
 */
class CacheRuleDetectionTest {

	private static final List<Case> CASES = List.of(
			new Case("cache: low hit rate → key/TTL/warm-up",
					Summaries.builder()
						.cache("RedisCache.get", 300,
								"900 ops, avg 0.3 ms · at com.acme.ProductService:51 — low hit rate (12% hits)")
						.build(),
					Mode.HINTS, List.of("low hit rate", "RedisCache.get"), List.of("N+1 query", "high error rate")),
			new Case("cache: a healthy cache fires nothing",
					Summaries.builder().cache("CaffeineCache.get", 20, "5000 ops, avg 0.0 ms").build(), Mode.HINTS,
					List.of(), List.of("low hit rate")),
			new Case("isolation: cache-op text does not trip another section's rule",
					Summaries.builder()
						.cache("SelectiveCache.get", 40, "10 ops, avg 4.0 ms · at com.acme.IteratorCache:7")
						.build(),
					Mode.HINTS, List.of(), List.of("SELECT *", "iterator allocation", "low hit rate")));

	@TestFactory
	Stream<DynamicTest> cacheRuleFixtures() {
		return RuleDetectionHarness.asTests(CASES);
	}

}
