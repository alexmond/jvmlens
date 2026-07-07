package org.alexmond.jvmlens.mongo;

import net.bytebuddy.asm.Advice;

/**
 * Inlined ByteBuddy advice timing a MongoDB {@code MongoCollection} operation and
 * recording its method name (find / insertOne / …) into {@link MongoStore}. Uses
 * {@code @Advice.Origin} (no reflection, no Mongo compile dependency) so it works across
 * driver versions; the operation is anchored to its app call-site by {@link MongoStore}.
 */
public final class MongoAdvice {

	private MongoAdvice() {
	}

	/** Capture the start time before the operation runs. */
	@Advice.OnMethodEnter(suppress = Throwable.class)
	public static long enter() {
		return System.nanoTime();
	}

	/** Record the elapsed time against the operation's method name. */
	@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.Origin("#m") String op) {
		MongoStore.record(op, System.nanoTime() - start);
	}

}
