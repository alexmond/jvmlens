package org.alexmond.jvmlens;

import java.util.List;

/**
 * Defines which stack frames count as <em>application</em> code for hot-path attribution.
 *
 * <p>
 * Without explicit includes, a frame is application code unless it belongs to the JDK or
 * a common framework (Spring, BouncyCastle, Jackson, logging, …) — plus any
 * caller-supplied excludes. With includes, <em>only</em> frames under the given package
 * prefixes count. The narrow JDK-only default used to let framework packages masquerade
 * as the user's code (see field-finding issue #1); the broadened default and the include
 * mode fix that.
 */
public record Scope(List<String> includePackages, List<String> excludePackages) {

	/** JDK / runtime packages — never application code. */
	private static final List<String> RUNTIME = List.of("java.", "jdk.", "sun.", "com.sun.", "javax.", "jakarta.");

	/** Common third-party frameworks — not the user's code by default. */
	private static final List<String> FRAMEWORKS = List.of("org.springframework.", "org.apache.", "org.bouncycastle.",
			"com.fasterxml.", "org.slf4j.", "ch.qos.logback.", "org.yaml.", "io.micrometer.", "io.netty.", "reactor.");

	/** The default scope: skip JDK + common frameworks, with no explicit includes. */
	public static Scope defaults() {
		return new Scope(List.of(), List.of());
	}

	/**
	 * Build a scope from (possibly null) CLI inputs.
	 * @param include package prefixes to treat as application code, or {@code null}
	 * @param exclude extra package prefixes to treat as non-application, or {@code null}
	 * @return the scope
	 */
	public static Scope of(List<String> include, List<String> exclude) {
		return new Scope((include != null) ? include : List.of(), (exclude != null) ? exclude : List.of());
	}

	/**
	 * Whether a frame owner (its declaring type name) counts as application code.
	 * @param owner the fully-qualified declaring type name
	 * @return {@code true} if it is application code under this scope
	 */
	public boolean isApplication(String owner) {
		if (!this.includePackages.isEmpty()) {
			return startsWithAny(owner, this.includePackages);
		}
		return !startsWithAny(owner, RUNTIME) && !startsWithAny(owner, FRAMEWORKS)
				&& !startsWithAny(owner, this.excludePackages);
	}

	private static boolean startsWithAny(String owner, List<String> prefixes) {
		for (String prefix : prefixes) {
			if (owner.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

}
