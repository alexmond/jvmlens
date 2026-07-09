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

	@Test
	void labelsTheRowWithTheCollectionNameFromTheAdvisedInstance() {
		// #147: the collection name is read reflectively off the advised
		// MongoCollection's
		// getNamespace().getCollectionName() — so the row is per-collection, not
		// method-only
		FakeCollection users = new FakeCollection("users");
		for (int i = 0; i < 60; i++) {
			MongoStore.record(users, "find", 1_000_000L);
		}
		assertThat(row("users.find").stack()).contains("60 ops").contains("possible N+1 document fetch");
	}

	@Test
	void flagStaysOpSuffixAwareSoTheCollectionPrefixDoesNotDefeatIt() {
		// the un-batched flag must fire on `orders.insertOne`, not just a bare
		// `insertOne`
		for (int i = 0; i < 60; i++) {
			MongoStore.record("orders.insertOne", 2_000_000L);
		}
		assertThat(row("orders.insertOne").stack()).contains("likely un-batched").doesNotContain("N+1");
	}

	@Test
	void degradesToTheBareOpWhenTheInstanceHasNoNamespace() {
		// fail-open: an unknown driver shape (no getNamespace) records the bare op, never
		// throws
		for (int i = 0; i < 60; i++) {
			MongoStore.record(new Object(), "find", 1_000_000L);
		}
		assertThat(row("find").stack()).contains("possible N+1 document fetch");
	}

	/**
	 * Stands in for a {@code MongoCollection}: exposes
	 * {@code getNamespace().getCollectionName()} (read reflectively by
	 * {@code MongoStore}).
	 */
	public static final class FakeCollection {

		private final String collection;

		FakeCollection(String collection) {
			this.collection = collection;
		}

		public FakeNamespace getNamespace() {
			return new FakeNamespace(this.collection);
		}

	}

	/** Stands in for a {@code MongoNamespace}: exposes {@code getCollectionName()}. */
	public static final class FakeNamespace {

		private final String collection;

		FakeNamespace(String collection) {
			this.collection = collection;
		}

		public String getCollectionName() {
			return this.collection;
		}

	}

}
