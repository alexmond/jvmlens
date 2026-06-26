package org.alexmond.jvmlens.cache;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Installs {@link CacheAdvice} on Spring's {@code org.springframework.cache.Cache}
 * abstraction (get/put/evict/putIfAbsent), matched by name so jvmlens needs no Spring
 * dependency. The Spring abstraction is the stable, framework-level seam covering Redis /
 * Caffeine / etc. behind one interface.
 */
public final class CacheCapture {

	private CacheCapture() {
	}

	/**
	 * Instrument cache operations on the given instrumentation.
	 * @param instrumentation the JVM instrumentation (from the agent or a test harness)
	 */
	public static void install(Instrumentation instrumentation) {
		new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(ElementMatchers.nameStartsWith("org.alexmond.jvmlens."))
			.type(ElementMatchers.hasSuperType(ElementMatchers.named("org.springframework.cache.Cache")))
			.transform((b, td, classLoader, module,
					pd) -> b.visit(Advice.to(CacheAdvice.class)
						.on(ElementMatchers.namedOneOf("get", "put", "evict", "putIfAbsent")
							.and(ElementMatchers.isPublic()))))
			.installOn(instrumentation);
	}

}
