package org.alexmond.jvmlens.sql;

import net.bytebuddy.asm.Advice;

/**
 * Inlined ByteBuddy advice wrapped around {@code java.sql.Statement.execute*}: times the
 * call and records the (sanitized) statement into {@link SqlStore}. The SQL comes from
 * the {@code String} argument for plain {@code Statement.execute(sql)}, or from the
 * statement's {@code toString()} for a {@code PreparedStatement} (most drivers — H2,
 * PostgreSQL — return the SQL there); unknown shapes degrade to {@code "?"}.
 */
public final class SqlAdvice {

	private SqlAdvice() {
	}

	/** Capture the start time before the query runs. */
	@Advice.OnMethodEnter(suppress = Throwable.class)
	public static long enter() {
		return System.nanoTime();
	}

	/** Record the elapsed time against the statement's sanitized shape. */
	@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.This Object statement,
			@Advice.AllArguments Object[] args) {
		String sql = null;
		if (args != null && args.length > 0 && args[0] instanceof String s) {
			sql = s;
		}
		else if (statement != null) {
			sql = statement.toString();
		}
		SqlStore.record(sql, System.nanoTime() - start);
	}

}
