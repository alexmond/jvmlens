package org.alexmond.jvmlens.probe;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The set of types every jvmlens instrumentation must refuse to touch. Shared by all the
 * {@code *Capture} installers so they apply it consistently.
 *
 * <p>
 * Crucially this is passed to {@code AgentBuilder.ignore(...)}, which
 * <strong>replaces</strong> ByteBuddy's default ignore matcher rather than adding to it.
 * The previous installers ignored only {@code org.alexmond.jvmlens.*}, which silently
 * dropped ByteBuddy's own safety ignores — so the broad
 * {@code isSubTypeOf}/{@code hasSuperType} type matchers got evaluated against
 * <em>synthetic / generated</em> classes (Hibernate entity enhancement, proxies, lambdas,
 * the agent's own auxiliary classes). Resolving those classes' super-type hierarchies
 * recurses and blows the stack during {@code EntityManagerFactory} build (field-finding
 * #68 Bug 2 → #70). Re-including ByteBuddy's defaults plus {@code isSynthetic()} restores
 * the protection.
 */
public final class AgentIgnores {

	private AgentIgnores() {
	}

	/**
	 * Types no jvmlens instrumentation should process: jvmlens itself, ByteBuddy, JDK
	 * internals, and any synthetic/generated type (whose hierarchy resolution recurses).
	 * @return the ignore matcher to hand to {@code AgentBuilder.ignore(...)}
	 */
	public static ElementMatcher.Junction<TypeDescription> base() {
		return ElementMatchers.nameStartsWith("org.alexmond.jvmlens.")
			.or(ElementMatchers.nameStartsWith("net.bytebuddy."))
			.or(ElementMatchers.nameStartsWith("sun.reflect."))
			.or(ElementMatchers.nameStartsWith("jdk.internal."))
			.or(ElementMatchers.isSynthetic());
	}

}
