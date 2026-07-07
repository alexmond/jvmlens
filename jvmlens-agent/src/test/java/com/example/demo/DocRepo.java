package com.example.demo;

import org.alexmond.jvmlens.mongo.MongoStore;

/**
 * A stand-in MongoDB repository in a non-jvmlens package, so {@link MongoStore}'s
 * call-site walk resolves to a realistic app frame (the {@code MongoStore.record} line is
 * the captured anchor).
 */
public final class DocRepo {

	private DocRepo() {
	}

	/**
	 * A document read (lazy in the real driver; here the call count is the N+1 signal).
	 */
	public static void find() {
		MongoStore.record("find", 1_000_000L);
	}

	/** A single-document insert (the un-batched-write shape). */
	public static void insertOne() {
		MongoStore.record("insertOne", 2_000_000L);
	}

	/** A batched insert (should never be flagged un-batched). */
	public static void insertMany() {
		MongoStore.record("insertMany", 3_000_000L);
	}

}
