package org.alexmond.jvmlens.snapshot;

import java.lang.instrument.Instrumentation;
import java.util.List;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Installs {@link SnapshotAdvice} on the requested methods via runtime retransformation,
 * so a target's variables are captured without stopping it. Each spec is
 * {@code fully.qualified.Class#method}.
 */
public final class SnapshotCapture {

	private SnapshotCapture() {
	}

	/**
	 * Instrument every {@code Class#method} in {@code specs} on the given
	 * instrumentation.
	 * @param instrumentation the JVM instrumentation (from the agent or a test harness)
	 * @param specs target specs, each {@code fully.qualified.Class#method}
	 */
	public static void install(Instrumentation instrumentation, List<String> specs) {
		AgentBuilder builder = new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
		for (String spec : specs) {
			int hash = spec.indexOf('#');
			if (hash <= 0) {
				continue;
			}
			String type = spec.substring(0, hash);
			String method = spec.substring(hash + 1);
			builder = builder.type(ElementMatchers.named(type))
				.transform((b, td, classLoader, module, pd) -> b
					.visit(Advice.to(SnapshotAdvice.class).on(ElementMatchers.named(method))));
		}
		builder.installOn(instrumentation);
	}

}
