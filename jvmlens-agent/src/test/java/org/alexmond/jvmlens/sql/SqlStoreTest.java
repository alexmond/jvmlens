package org.alexmond.jvmlens.sql;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStoreTest {

	@BeforeEach
	@AfterEach
	void clear() {
		SqlStore.reset();
	}

	@Test
	void emptyStoreHasNoSection() {
		assertThat(SqlStore.sections()).isEmpty();
	}

	@Test
	void aggregatesByShapeAndRanksByTotalTime() {
		// one slow distinct query vs many fast ones of the same shape
		SqlStore.record("SELECT * FROM report WHERE day = 1", 200_000_000L); // 200ms, 1
																				// call
		for (int i = 0; i < 60; i++) {
			SqlStore.record("SELECT * FROM line WHERE id = " + i, 1_000_000L); // 1ms
																				// each,
																				// same
																				// shape
		}
		List<Section> sections = SqlStore.sections();
		assertThat(sections).hasSize(1);
		Section db = sections.get(0);
		assertThat(db.key()).isEqualTo("db");
		assertThat(db.measured()).isTrue();
		List<Ranked> rows = db.rows();
		// the 200ms report query has the most total time → ranked first
		assertThat(rows.get(0).name()).isEqualTo("select * from report where day = ?");
		assertThat(rows.get(0).stack()).contains("1 calls");
		// the 60x line query aggregated to one shape, flagged as possible N+1
		Ranked line = rows.stream().filter((r) -> r.name().contains("line")).findFirst().orElseThrow();
		assertThat(line.stack()).contains("60 calls").contains("possible N+1");
	}

	@Test
	void capturesTheAppCallSiteWhenScopeIsSet() {
		org.alexmond.jvmlens.probe.CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 60; i++) {
			com.example.demo.OrderDao.runQuery("SELECT * FROM orders WHERE id = " + i);
		}
		Ranked row = SqlStore.sections().get(0).rows().get(0);
		// anchored to the app frame (OrderDao), never to jvmlens's own classes
		assertThat(row.stack()).contains("· at com.example.demo.OrderDao:").contains("possible N+1");
	}

	@Test
	void capturesNoCallSiteWithoutScope() {
		com.example.demo.OrderDao.runQuery("SELECT 1");
		assertThat(SqlStore.sections().get(0).rows().get(0).stack()).doesNotContain("· at");
	}

	@Test
	void repeatedSingleRowInsertReadsAsUnbatched() {
		for (int i = 0; i < 60; i++) {
			SqlStore.record("INSERT INTO orders (id, total) VALUES (" + i + ", " + i + ")", 2_000_000L);
		}
		Ranked row = SqlStore.sections().get(0).rows().get(0);
		assertThat(row.stack()).contains("60 calls").contains("likely un-batched").doesNotContain("possible N+1");
	}

	@Test
	void anAlreadyBatchedInSelectIsNotFlaggedAsNplusOne() {
		// a repeated `WHERE id IN (…)` is the batch-fetch fix, not the N+1 anti-pattern
		for (int i = 0; i < 60; i++) {
			SqlStore.record("SELECT * FROM line WHERE id IN (" + i + ", " + (i + 1) + ", " + (i + 2) + ")", 1_000_000L);
		}
		Ranked row = SqlStore.sections().get(0).rows().get(0);
		assertThat(row.stack()).contains("60 calls").doesNotContain("possible N+1");
	}

	@Test
	void resetClearsState() {
		SqlStore.record("SELECT 1", 1_000_000L);
		assertThat(SqlStore.sections()).isNotEmpty();
		SqlStore.reset();
		assertThat(SqlStore.sections()).isEmpty();
	}

}
