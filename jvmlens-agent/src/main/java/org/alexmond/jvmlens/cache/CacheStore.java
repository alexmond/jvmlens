package org.alexmond.jvmlens.cache;

import java.util.List;

import org.alexmond.jvmlens.ProfileSummary.Section;
import org.alexmond.jvmlens.probe.OpStore;

/**
 * Static facade over an {@link OpStore} for cache operations (Spring {@code Cache}
 * get/put/evict) captured by {@code CacheAdvice}, rendered as the {@code cache} extended
 * section.
 */
public final class CacheStore {

	private static final OpStore STORE = new OpStore();

	private CacheStore() {
	}

	/** Record one cache operation {@code op} taking {@code nanos}; called from advice. */
	public static void record(String op, long nanos) {
		STORE.record(op, nanos);
	}

	/** Clear all captured operations (used by tests). */
	public static void reset() {
		STORE.reset();
	}

	/** The captured operations as the {@code cache} section, or empty. */
	public static List<Section> sections() {
		return STORE.sections("cache", "Cache operations (by total time)");
	}

}
