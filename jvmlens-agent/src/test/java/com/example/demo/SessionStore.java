package com.example.demo;

import org.alexmond.jvmlens.redis.RedisStore;

/**
 * A stand-in direct-Redis component in a non-jvmlens package, so {@link RedisStore}'s
 * call-site walk resolves to a realistic app frame (the {@code RedisStore.record} line is
 * the captured anchor).
 */
public final class SessionStore {

	private SessionStore() {
	}

	/** A single-key read (the per-key round-trip shape). */
	public static void get() {
		RedisStore.record("get", 300_000L);
	}

	/** A batched multi-key read (should never be flagged). */
	public static void mget() {
		RedisStore.record("mget", 700_000L);
	}

}
