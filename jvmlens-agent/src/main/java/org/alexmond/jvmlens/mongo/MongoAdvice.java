package org.alexmond.jvmlens.mongo;

import net.bytebuddy.asm.Advice;

/**
 * Inlined ByteBuddy advice timing a MongoDB {@code MongoCollection} operation and
 * recording it into {@link MongoStore} as {@code <collection>.<method>} (e.g.
 * {@code users.find}) — the collection name is read from the advised instance's
 * {@code getNamespace()} reflectively by {@link MongoStore} (no Mongo compile
 * dependency), so per-collection N+1 stands out instead of a method-only roll-up (#147).
 * Binds {@code @This} (mirroring {@code SqlAdvice}) plus {@code @Advice.Origin} for the
 * method name; the operation is anchored to its app call-site by {@link MongoStore}.
 */
public final class MongoAdvice {

	private MongoAdvice() {
	}

	/** Capture the start time before the operation runs. */
	@Advice.OnMethodEnter(suppress = Throwable.class)
	public static long enter() {
		return System.nanoTime();
	}

	/** Record the elapsed time against the collection + operation. */
	@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.This Object collection, @Advice.Origin("#m") String op) {
		MongoStore.record(collection, op, System.nanoTime() - start);
	}

}
