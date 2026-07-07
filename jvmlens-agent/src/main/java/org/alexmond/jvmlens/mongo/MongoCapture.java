package org.alexmond.jvmlens.mongo;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import org.alexmond.jvmlens.probe.AgentIgnores;

/**
 * Installs {@link MongoAdvice} on the sync MongoDB driver's {@code MongoCollection}
 * operations (matched by interface name, so jvmlens needs no Mongo dependency). Tight
 * scope: only the collection-level find/aggregate/insert/update/delete/count entry
 * points, not the whole driver. Note {@code find}/{@code aggregate} return lazy
 * iterables, so their <em>latency</em> is construction-only — the <em>count</em> is the
 * N+1 signal.
 */
public final class MongoCapture {

	private MongoCapture() {
	}

	/**
	 * Instrument MongoDB collection operations on the given instrumentation.
	 * @param instrumentation the JVM instrumentation (from the agent or a test harness)
	 */
	public static void install(Instrumentation instrumentation) {
		new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(AgentIgnores.base())
			.type(ElementMatchers.hasSuperType(ElementMatchers.named("com.mongodb.client.MongoCollection")))
			.transform((b, td, classLoader, module,
					pd) -> b.visit(Advice.to(MongoAdvice.class)
						.on(ElementMatchers
							.namedOneOf("find", "aggregate", "insertOne", "insertMany", "updateOne", "updateMany",
									"replaceOne", "deleteOne", "deleteMany", "countDocuments", "bulkWrite")
							.and(ElementMatchers.isPublic()))))
			.installOn(instrumentation);
	}

}
