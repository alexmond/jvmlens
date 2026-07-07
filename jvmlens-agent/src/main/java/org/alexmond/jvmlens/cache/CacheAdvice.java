package org.alexmond.jvmlens.cache;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined ByteBuddy advice timing a cache operation and recording its
 * {@code Class.method} origin into {@link CacheStore}. Uses {@code @Advice.Origin} (no
 * reflection) so it works across the concrete cache implementations behind Spring's
 * {@code Cache} abstraction. For a {@code get}, a {@code null} return is a cache
 * <em>miss</em> — the hit/miss split drives the low-hit-rate hint.
 */
public final class CacheAdvice {

	private CacheAdvice() {
	}

	/** Capture the start time before the cache operation runs. */
	@Advice.OnMethodEnter(suppress = Throwable.class)
	public static long enter() {
		return System.nanoTime();
	}

	/**
	 * Record the elapsed time + (for a {@code get}) hit/miss against the origin label.
	 */
	@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.Origin("#t.#m") String op,
			@Advice.Origin("#m") String method,
			@Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = true) Object result) {
		Boolean hit = "get".equals(method) ? (result != null) : null;
		CacheStore.record(op, System.nanoTime() - start, hit);
	}

}
