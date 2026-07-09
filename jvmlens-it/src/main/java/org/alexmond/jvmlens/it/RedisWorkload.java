package org.alexmond.jvmlens.it;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * A tiny non-Spring workload for {@code RedisInstrumentationIT}: connect to a Redis (the
 * URI is {@code args[0]}), fire more than the N+1 threshold of single-key {@code get}s
 * from a stable application call-site, let the attached agent tick, then exit 0. The
 * Lettuce sync API delegates to the async {@code RedisAsyncCommands} the agent matches,
 * so a sync workload still fires the advice — proving the {@code redis} dimension
 * end-to-end against a real driver (#146).
 */
public final class RedisWorkload {

	private RedisWorkload() {
	}

	public static void main(String[] args) throws Exception {
		RedisClient client = RedisClient.create(args[0]);
		try (StatefulRedisConnection<String, String> conn = client.connect()) {
			RedisCommands<String, String> redis = conn.sync();
			redis.set("k", "v");
			readOneByOne(redis);
		}
		finally {
			client.shutdown();
		}
		Thread.sleep(3000); // let the agent tick at least once and write its summary
		System.out.println("JVMLENS-IT-READY");
		System.exit(0);
	}

	/**
	 * More than 50 single-key reads from one app call-site → the redis N+1-round-trips
	 * flag.
	 */
	private static void readOneByOne(RedisCommands<String, String> redis) {
		for (int i = 0; i < 60; i++) {
			redis.get("k");
		}
	}

}
