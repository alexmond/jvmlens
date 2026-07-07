package org.alexmond.jvmlens.cache;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.probe.CallSites;

import static org.assertj.core.api.Assertions.assertThat;

class CacheStoreTest {

	@BeforeEach
	@AfterEach
	void clear() {
		CacheStore.reset();
	}

	@Test
	void capturesTheCallerAndFlagsLowHitRate() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 80; i++) {
			com.example.demo.ProductService.get(false); // miss
		}
		for (int i = 0; i < 20; i++) {
			com.example.demo.ProductService.get(true); // hit → 20% over 100 gets
		}
		Ranked row = CacheStore.sections().get(0).rows().get(0);
		assertThat(row.stack()).contains("· at com.example.demo.ProductService:").contains("low hit rate (20% hits)");
	}

	@Test
	void noLowHitRateFlagWhenHealthy() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 90; i++) {
			com.example.demo.ProductService.get(true);
		}
		for (int i = 0; i < 10; i++) {
			com.example.demo.ProductService.get(false); // 90% hits
		}
		assertThat(CacheStore.sections().get(0).rows().get(0).stack()).doesNotContain("low hit rate");
	}

	@Test
	void putIsNotCountedAsAMiss() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 30; i++) {
			CacheStore.record("com.acme.RedisCache.put", 100_000L, null); // not a get
		}
		assertThat(CacheStore.sections().get(0).rows().get(0).stack()).doesNotContain("low hit rate");
	}

}
