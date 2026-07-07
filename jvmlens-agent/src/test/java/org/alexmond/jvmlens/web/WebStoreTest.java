package org.alexmond.jvmlens.web;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

import static org.assertj.core.api.Assertions.assertThat;

class WebStoreTest {

	@BeforeEach
	@AfterEach
	void clear() {
		WebStore.reset();
	}

	@Test
	void emptyStoreHasNoSection() {
		assertThat(WebStore.sections()).isEmpty();
	}

	@Test
	void aggregatesByRouteShapeRanksByTimeAndCountsErrors() {
		// same route, different ids → one shape; one of them a 500
		WebStore.record("GET", "/orders/1", 200, 100_000_000L);
		WebStore.record("GET", "/orders/2", 500, 100_000_000L);
		WebStore.record("POST", "/login", 200, 5_000_000L);
		List<Section> sections = WebStore.sections();
		assertThat(sections).hasSize(1);
		Section web = sections.get(0);
		assertThat(web.key()).isEqualTo("web");
		List<Ranked> rows = web.rows();
		// the orders shape has the most total time → first, 2 reqs, 1 error
		assertThat(rows.get(0).name()).isEqualTo("GET /orders/{}");
		assertThat(rows.get(0).stack()).contains("2 reqs").contains("1 errors");
		// the login route is a separate, faster endpoint with no errors
		Ranked login = rows.stream().filter((r) -> r.name().contains("/login")).findFirst().orElseThrow();
		assertThat(login.stack()).doesNotContain("errors");
	}

	@Test
	void capturesTheHandlerCallSiteAndFlagsHighErrorRate() {
		org.alexmond.jvmlens.probe.CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 8; i++) {
			com.example.demo.UserController.handle(200);
		}
		for (int i = 0; i < 4; i++) {
			com.example.demo.UserController.handle(500); // 4/12 = 33% > 20% threshold
		}
		Ranked row = WebStore.sections().get(0).rows().get(0);
		assertThat(row.stack()).contains("· at com.example.demo.UserController:")
			.contains("4 errors")
			.contains("high error rate");
	}

	@Test
	void noHighErrorRateFlagBelowThreshold() {
		org.alexmond.jvmlens.probe.CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 20; i++) {
			com.example.demo.UserController.handle(200); // one error in 21 → below 20%
		}
		com.example.demo.UserController.handle(500);
		assertThat(WebStore.sections().get(0).rows().get(0).stack()).doesNotContain("high error rate");
	}

	@Test
	void resetClearsState() {
		WebStore.record("GET", "/x", 200, 1_000_000L);
		assertThat(WebStore.sections()).isNotEmpty();
		WebStore.reset();
		assertThat(WebStore.sections()).isEmpty();
	}

}
