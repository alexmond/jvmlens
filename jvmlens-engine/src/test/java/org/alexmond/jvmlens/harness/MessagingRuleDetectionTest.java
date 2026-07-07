package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.alexmond.jvmlens.harness.RuleDetectionHarness.Case;
import org.alexmond.jvmlens.harness.RuleDetectionHarness.Mode;

/**
 * P1d — the {@code messaging} extended section now feeds {@code --hints}. The defensible,
 * well-gated lever is a synchronous per-message send (high volume + material latency;
 * Kafka's fast async sends stay under the gate and are flagged by the store, not here).
 * Guards: a fast/async producer and a consumer poll fire nothing.
 */
class MessagingRuleDetectionTest {

	private static final List<Case> CASES = List.of(
			new Case("messaging: synchronous per-message send → batch/async",
					Summaries.builder()
						.messaging("JmsTemplate.send", 900,
								"300 ops, avg 3.0 ms · at com.acme.OrderPublisher:64 — synchronous per-message send")
						.build(),
					Mode.HINTS, List.of("synchronous per-message send", "JmsTemplate.send"),
					List.of("N+1 query", "low hit rate")),
			new Case("messaging: a fast async producer fires nothing",
					Summaries.builder().messaging("KafkaProducer.send", 5, "5000 ops, avg 0.0 ms").build(), Mode.HINTS,
					List.of(), List.of("synchronous per-message send")),
			new Case("messaging: a consumer poll fires nothing",
					Summaries.builder().messaging("KafkaConsumer.poll", 200, "40 ops, avg 5.0 ms").build(), Mode.HINTS,
					List.of(), List.of("synchronous per-message send")));

	@TestFactory
	Stream<DynamicTest> messagingRuleFixtures() {
		return RuleDetectionHarness.asTests(CASES);
	}

}
