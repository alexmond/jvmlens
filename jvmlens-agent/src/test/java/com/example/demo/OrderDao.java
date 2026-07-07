package com.example.demo;

import org.alexmond.jvmlens.sql.SqlStore;

/**
 * A stand-in "application" DAO in a non-jvmlens package, so {@link SqlStore}'s call-site
 * walk resolves to a realistic app frame (as it would in a real target) rather than to
 * jvmlens's own classes.
 */
public final class OrderDao {

	private OrderDao() {
	}

	/**
	 * Issues a "query": the {@code SqlStore.record} line below is the captured call-site.
	 */
	public static void runQuery(String sql) {
		SqlStore.record(sql, 1_000_000L);
	}

}
