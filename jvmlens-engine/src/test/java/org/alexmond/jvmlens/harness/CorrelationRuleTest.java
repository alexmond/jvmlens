package org.alexmond.jvmlens.harness;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.alexmond.jvmlens.harness.RuleDetectionHarness.Case;
import org.alexmond.jvmlens.harness.RuleDetectionHarness.Mode;

/**
 * P2a — the cross-dimension correlation now renders an ordered chain that surfaces each
 * link's P1 source anchor + a compact flag, weaving web/db/cache/messaging with GC, and
 * stays honest ("co-occurrence, not proof"). Guards: the anchors and flags appear; a
 * startup-dominated capture is softened, not asserted as a workload chain (#70).
 */
class CorrelationRuleTest {

	private static final List<Case> CASES = List.of(
			new Case("correlation: anchored endpoint → query → GC chain",
					Summaries.builder()
						.web("GET /users", 500,
								"120 reqs, avg 4.0 ms · at com.acme.UserController:37, 40 errors — high error rate")
						.db("select * from users where id = ?", 480,
								"60 calls, avg 8.0 ms · at com.acme.UserRepo:88 — high call count, possible N+1")
						.gcPauseMillis(820)
						.hotPath("com.acme.UserService.list", 0.3, 300, null)
						.build(),
					Mode.MARKDOWN,
					List.of("Cross-dimension correlation",
							"endpoint `GET /users` @ UserController:37 (high error rate)",
							"SQL `select * from users where id = ?` @ UserRepo:88 (N+1)", "GC `820 ms`",
							"Co-occurrence, not proof"),
					List.of("startup/classload-dominated")),
			new Case("correlation: startup-dominated capture is softened, not asserted",
					Summaries.builder()
						.db("create table users (id bigint)", 200, "1 calls, avg 200.0 ms")
						.hotPath("org.springframework.boot.SpringApplication.run", 0.6, 600, null)
						.gcPauseMillis(300)
						.build(),
					Mode.MARKDOWN, List.of("startup/classload-dominated", "Co-occurring (not necessarily a workload"),
					List.of("Likely chain: endpoint")));

	@TestFactory
	Stream<DynamicTest> correlationFixtures() {
		return RuleDetectionHarness.asTests(CASES);
	}

}
