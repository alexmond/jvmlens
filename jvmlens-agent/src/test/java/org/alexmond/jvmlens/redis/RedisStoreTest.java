package org.alexmond.jvmlens.redis;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.probe.CallSites;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStoreTest {

	@BeforeEach
	@AfterEach
	void clear() {
		RedisStore.reset();
	}

	private static Ranked row(String op) {
		return RedisStore.sections()
			.get(0)
			.rows()
			.stream()
			.filter((r) -> r.name().equals(op))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void manySingleKeyGetsReadAsNplusOneRoundTrips() {
		for (int i = 0; i < 60; i++) {
			RedisStore.record("get", 300_000L);
		}
		assertThat(row("get").stack()).contains("60 ops").contains("possible N+1 round-trips");
	}

	@Test
	void anAlreadyBatchedMgetIsNotFlagged() {
		for (int i = 0; i < 60; i++) {
			RedisStore.record("mget", 700_000L);
		}
		assertThat(row("mget").stack()).doesNotContain("round-trips");
	}

	@Test
	void anchorsToTheAppCallSiteWhenScopeIsSet() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 60; i++) {
			com.example.demo.SessionStore.get();
		}
		assertThat(row("get").stack()).contains("· at com.example.demo.SessionStore:")
			.contains("possible N+1 round-trips");
	}

}
