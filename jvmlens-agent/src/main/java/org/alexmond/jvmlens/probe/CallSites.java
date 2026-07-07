package org.alexmond.jvmlens.probe;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures the first <em>application</em> stack frame issuing an instrumented operation
 * (a SQL execution, an HTTP request, a cache/messaging op) as a
 * {@code com.acme.OrderRepo:88} anchor, so a semantic-dimension row can point at the
 * exact call-site the way CPU and allocation rows do (#87 form factor). Shared by every
 * extended dimension.
 *
 * <p>
 * <strong>Scope-gated</strong>: no walk happens until the agent sets the application
 * prefixes (from its live {@code scope}), so there is zero added cost otherwise.
 * <strong>Bounded</strong>: at most {@link #MAX_WALK} frames per op. Callers run it
 * inside their {@code FailGuard}, so it is fail-open.
 */
public final class CallSites {

	/** Cap the per-operation stack walk so capture stays cheap. */
	private static final int MAX_WALK = 40;

	private static final AtomicReference<String[]> APP_PREFIXES = new AtomicReference<>(new String[0]);

	private CallSites() {
	}

	/**
	 * Set the application-frame prefixes used to anchor operations to their call-site
	 * (the agent refreshes this from the live {@code scope}); {@code null}/empty disables
	 * the walk.
	 */
	public static void setAppScope(List<String> prefixes) {
		APP_PREFIXES.set((prefixes != null) ? prefixes.toArray(new String[0]) : new String[0]);
	}

	/**
	 * The first application stack frame ({@code com.acme.OrderRepo:88}) above the caller,
	 * or {@code null} when no scope is set or no app frame carries a line.
	 */
	public static String capture() {
		String[] prefixes = APP_PREFIXES.get();
		if (prefixes.length == 0) {
			return null;
		}
		return StackWalker.getInstance()
			.walk((frames) -> frames.limit(MAX_WALK)
				.filter((f) -> f.getLineNumber() > 0 && startsWithAny(f.getClassName(), prefixes))
				.map((f) -> f.getClassName() + ":" + f.getLineNumber())
				.findFirst()
				.orElse(null));
	}

	/** Clear the configured scope (used by tests). */
	public static void reset() {
		APP_PREFIXES.set(new String[0]);
	}

	private static boolean startsWithAny(String owner, String[] prefixes) {
		for (String prefix : prefixes) {
			if (owner.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

}
