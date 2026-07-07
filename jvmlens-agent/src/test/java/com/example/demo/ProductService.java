package com.example.demo;

import org.alexmond.jvmlens.cache.CacheStore;

/**
 * A stand-in {@code @Cacheable} application service in a non-jvmlens package, so
 * {@link CacheStore}'s call-site walk resolves to a realistic caller frame.
 */
public final class ProductService {

	private ProductService() {
	}

	/**
	 * A cache get: the {@code CacheStore.record} line below is the captured call-site.
	 */
	public static void get(boolean hit) {
		CacheStore.record("com.acme.RedisCache.get", 300_000L, hit);
	}

}
