package org.alexmond.jvmlens.sql;

import java.lang.instrument.Instrumentation;
import java.sql.Statement;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import org.alexmond.jvmlens.probe.AgentIgnores;

/**
 * Installs {@link SqlAdvice} on every concrete {@code java.sql.Statement}
 * implementation's {@code execute*} methods via runtime retransformation, so JDBC timing
 * is captured without touching the application. Scoped tightly (only {@code Statement}
 * subtypes, only {@code execute*}) per the extended-profiling "tight version scope" rule.
 */
public final class SqlCapture {

	private SqlCapture() {
	}

	/**
	 * Instrument JDBC statement execution on the given instrumentation.
	 * @param instrumentation the JVM instrumentation (from the agent or a test harness)
	 */
	public static void install(Instrumentation instrumentation) {
		new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(AgentIgnores.base())
			.type(ElementMatchers.isSubTypeOf(Statement.class).and(ElementMatchers.not(ElementMatchers.isInterface())))
			.transform((b, td, classLoader, module,
					pd) -> b.visit(Advice.to(SqlAdvice.class)
						.on(ElementMatchers.nameStartsWith("execute").and(ElementMatchers.isPublic()))))
			.installOn(instrumentation);
	}

}
