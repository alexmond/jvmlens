package org.alexmond.jvmlens.cache;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;
import org.alexmond.jvmlens.probe.CallSites;
import org.alexmond.jvmlens.probe.FailGuard;
import org.alexmond.jvmlens.probe.OpStore;

/**
 * Static facade over an {@link OpStore} for cache operations (Spring {@code Cache}
 * get/put/evict) captured by {@code CacheAdvice}, rendered as the {@code cache} extended
 * section. Each op is anchored to its application call-site (via {@link CallSites}), and
 * {@code get} hit/miss is tracked so a mostly-missing cache is flagged {@code low hit
 * rate} — the crisp cache lever.
 */
public final class CacheStore {

	/** Below this many gets a hit rate isn't statistically meaningful. */
	private static final long HIT_RATE_MIN_GETS = 20;

	/** Hit fraction below which a cache reads as "not paying for itself". */
	private static final double LOW_HIT_RATE = 0.5;

	private static final OpStore STORE = new OpStore();

	/**
	 * get hit/miss counters, keyed by the shortened op label (e.g.
	 * {@code RedisCache.get}).
	 */
	private static final Map<String, HitMiss> HITS = new ConcurrentHashMap<>();

	private CacheStore() {
	}

	/**
	 * Record one cache operation {@code op} taking {@code nanos}; {@code hit} is
	 * {@code true}/{@code false} for a get, {@code null} for put/evict. Called from
	 * advice.
	 */
	public static void record(String op, long nanos, Boolean hit) {
		FailGuard.run("cache", () -> {
			STORE.record(op, nanos, CallSites.capture());
			if (hit != null) {
				HITS.computeIfAbsent(OpStore.shorten(op), (k) -> new HitMiss()).add(hit);
			}
		});
	}

	/** Clear all captured operations, hit/miss, and scope (used by tests). */
	public static void reset() {
		STORE.reset();
		HITS.clear();
		CallSites.reset();
	}

	/** The captured operations as the {@code cache} section, or empty. */
	public static List<Section> sections() {
		List<Section> base = STORE.sections("cache", "Cache operations (by total time)");
		if (base.isEmpty()) {
			return base;
		}
		Section s = base.get(0);
		List<Ranked> rows = s.rows().stream().map(CacheStore::withHitRate).toList();
		return List.of(new Section(s.key(), s.title(), s.unit(), s.measured(), rows));
	}

	/**
	 * Append a low-hit-rate flag to a get row whose hit rate is poor over enough gets.
	 */
	private static Ranked withHitRate(Ranked row) {
		HitMiss hm = HITS.get(row.name());
		if (hm == null) {
			return row;
		}
		long total = hm.hits.get() + hm.misses.get();
		double rate = (total > 0) ? (double) hm.hits.get() / total : 1.0;
		if (total < HIT_RATE_MIN_GETS || rate >= LOW_HIT_RATE) {
			return row;
		}
		String flag = String.format(Locale.ROOT, " — low hit rate (%d%% hits)", Math.round(rate * 100));
		return new Ranked(row.name(), row.share(), row.count(), row.stack() + flag);
	}

	private static final class HitMiss {

		private final AtomicLong hits = new AtomicLong();

		private final AtomicLong misses = new AtomicLong();

		void add(boolean hit) {
			(hit ? this.hits : this.misses).incrementAndGet();
		}

	}

}
