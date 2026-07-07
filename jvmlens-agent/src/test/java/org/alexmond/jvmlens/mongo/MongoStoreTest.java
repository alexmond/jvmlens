package org.alexmond.jvmlens.mongo;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.probe.CallSites;

import static org.assertj.core.api.Assertions.assertThat;

class MongoStoreTest {

	@BeforeEach
	@AfterEach
	void clear() {
		MongoStore.reset();
	}

	private static Ranked row(String op) {
		return MongoStore.sections()
			.get(0)
			.rows()
			.stream()
			.filter((r) -> r.name().equals(op))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void repeatedFindReadsAsPossibleNplusOneDocumentFetch() {
		for (int i = 0; i < 60; i++) {
			MongoStore.record("find", 1_000_000L);
		}
		assertThat(row("find").stack()).contains("60 ops").contains("possible N+1 document fetch");
	}

	@Test
	void repeatedSingleDocumentInsertReadsAsUnbatched() {
		for (int i = 0; i < 60; i++) {
			MongoStore.record("insertOne", 2_000_000L);
		}
		assertThat(row("insertOne").stack()).contains("likely un-batched").doesNotContain("N+1");
	}

	@Test
	void anAlreadyBatchedInsertManyIsNotFlagged() {
		for (int i = 0; i < 60; i++) {
			MongoStore.record("insertMany", 3_000_000L);
		}
		assertThat(row("insertMany").stack()).doesNotContain("un-batched").doesNotContain("N+1");
	}

	@Test
	void anchorsToTheAppCallSiteWhenScopeIsSet() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 60; i++) {
			com.example.demo.DocRepo.find();
		}
		assertThat(row("find").stack()).contains("· at com.example.demo.DocRepo:")
			.contains("possible N+1 document fetch");
	}

}
