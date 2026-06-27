package org.alexmond.jvmlens.web;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import org.alexmond.jvmlens.probe.AgentIgnores;

/**
 * Installs {@link WebAdvice} on the {@code service(request, response)} method of every
 * {@code HttpServlet} subtype (both {@code jakarta.servlet} and {@code javax.servlet}, by
 * name so jvmlens needs no servlet dependency), capturing HTTP endpoint timing without
 * touching the application. Spring MVC's {@code DispatcherServlet} is such a subtype, so
 * a single instrumentation point covers Spring Boot web apps.
 */
public final class WebCapture {

	private WebCapture() {
	}

	/**
	 * Instrument servlet request handling on the given instrumentation.
	 * @param instrumentation the JVM instrumentation (from the agent or a test harness)
	 */
	public static void install(Instrumentation instrumentation) {
		new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(AgentIgnores.base())
			.type(ElementMatchers.hasSuperType(ElementMatchers.named("jakarta.servlet.http.HttpServlet")
				.or(ElementMatchers.named("javax.servlet.http.HttpServlet"))))
			.transform((b, td, classLoader, module,
					pd) -> b.visit(Advice.to(WebAdvice.class)
						.on(ElementMatchers.named("service").and(ElementMatchers.takesArguments(2)))))
			.installOn(instrumentation);
	}

}
