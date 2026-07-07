package org.alexmond.jvmlens.redis;

import java.util.List;
import java.util.Set;

import org.alexmond.jvmlens.ProfileSummary.Section;
import org.alexmond.jvmlens.probe.CallSites;
import org.alexmond.jvmlens.probe.FailGuard;
import org.alexmond.jvmlens.probe.OpStore;

/**
 * Static facade over an {@link OpStore} for direct Redis commands (Lettuce / Jedis
 * {@code GET}/{@code SET}/{@code HGET}/…) captured by {@code RedisAdvice}, rendered as
 * the {@code redis} extended section. Distinct from the {@code cache} dimension: that
 * tracks Spring {@code Cache} hit rate, this tracks raw command traffic for apps hitting
 * Redis directly (session stores, rate limiters, queues). Each op is anchored to its app
 * call-site (via {@link CallSites}); a high count of single-key reads reads as N+1 round
 * trips — the crisp Redis lever (pipeline or a multi-key command).
 */
public final class RedisStore {

	/** A command repeated at least this many times is worth flagging. */
	private static final long N_PLUS_ONE_CALLS = 50;

	/**
	 * Single-key read commands (lowercased method names) — repeating these one key at a
	 * time is the round-trip anti-pattern; the multi-key variants (MGET/HMGET) and
	 * pipelining are the fix. Multi-key commands are deliberately absent (already
	 * batched).
	 */
	private static final Set<String> SINGLE_KEY_READS = Set.of("get", "hget", "lindex", "zscore", "sismember", "exists",
			"getex");

	private static final OpStore STORE = new OpStore();

	private RedisStore() {
	}

	/** Record one Redis command {@code op} taking {@code nanos}; called from advice. */
	public static void record(String op, long nanos) {
		FailGuard.run("redis", () -> {
			List<String> path = CallSites.capturePath();
			STORE.record(op, nanos, CallSites.site(path), CallSites.entryClass(path));
		});
	}

	/** Clear all captured commands and scope (used by tests). */
	public static void reset() {
		STORE.reset();
		CallSites.reset();
	}

	/** The captured commands as the {@code redis} section, or empty. */
	public static List<Section> sections() {
		return STORE.sections("redis", "Redis commands (by total time)", RedisStore::flag);
	}

	private static String flag(String label, long calls, long nanos) {
		if (calls >= N_PLUS_ONE_CALLS && SINGLE_KEY_READS.contains(label.toLowerCase(java.util.Locale.ROOT))) {
			return " — many single-key reads, possible N+1 round-trips";
		}
		return "";
	}

}
