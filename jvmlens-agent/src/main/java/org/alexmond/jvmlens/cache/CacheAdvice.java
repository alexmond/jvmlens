package org.alexmond.jvmlens.cache;

import net.bytebuddy.asm.Advice;

/**
 * Inlined ByteBuddy advice timing a cache operation and recording its
 * {@code Class.method} origin into {@link CacheStore}. Uses {@code @Advice.Origin} (no
 * reflection) so it works across the concrete cache implementations behind Spring's
 * {@code Cache} abstraction.
 */
public final class CacheAdvice {

	private CacheAdvice() {
	}

	/** Capture the start time before the cache operation runs. */
	@Advice.OnMethodEnter
	public static long enter() {
		return System.nanoTime();
	}

	/** Record the elapsed time against the operation's origin label. */
	@Advice.OnMethodExit(onThrowable = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.Origin("#t.#m") String op) {
		CacheStore.record(op, System.nanoTime() - start);
	}

}
