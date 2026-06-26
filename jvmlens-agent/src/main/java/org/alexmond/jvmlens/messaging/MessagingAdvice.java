package org.alexmond.jvmlens.messaging;

import net.bytebuddy.asm.Advice;

/**
 * Inlined ByteBuddy advice timing a messaging operation and recording its
 * {@code Class.method} origin into {@link MessagingStore}. Uses {@code @Advice.Origin}
 * (no reflection, version-agnostic) so it works across Kafka / JMS implementations.
 */
public final class MessagingAdvice {

	private MessagingAdvice() {
	}

	/** Capture the start time before the operation runs. */
	@Advice.OnMethodEnter
	public static long enter() {
		return System.nanoTime();
	}

	/** Record the elapsed time against the operation's origin label. */
	@Advice.OnMethodExit(onThrowable = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.Origin("#t.#m") String op) {
		MessagingStore.record(op, System.nanoTime() - start);
	}

}
