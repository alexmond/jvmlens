package org.alexmond.jvmlens.redis;

import net.bytebuddy.asm.Advice;

/**
 * Inlined ByteBuddy advice timing a Redis command and recording its method name (get /
 * set / hget / …) into {@link RedisStore}. Uses {@code @Advice.Origin} (no reflection, no
 * Redis compile dependency) so it works across Lettuce / Jedis versions.
 */
public final class RedisAdvice {

	private RedisAdvice() {
	}

	/** Capture the start time before the command runs. */
	@Advice.OnMethodEnter(suppress = Throwable.class)
	public static long enter() {
		return System.nanoTime();
	}

	/** Record the elapsed time against the command's method name. */
	@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.Origin("#m") String op) {
		RedisStore.record(op, System.nanoTime() - start);
	}

}
