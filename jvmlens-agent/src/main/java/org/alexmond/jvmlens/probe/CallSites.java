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

	/** Cap the retained application call-path (P2b linkage) — dominant path only. */
	private static final int MAX_APP_FRAMES = 8;

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

	/**
	 * The bounded application call-path issuing an operation — the app frames
	 * ({@code com.acme.OrderRepo:88}) innermost→outermost, at most
	 * {@link #MAX_APP_FRAMES}, or empty when no scope is set. The first element equals
	 * {@link #capture()} (the anchor); the last is the outermost app caller (the request
	 * entry). Drives P2b's shared-call-path linkage — a deeper op whose path passes
	 * through an endpoint's handler class provably ran inside that request.
	 */
	public static List<String> capturePath() {
		String[] prefixes = APP_PREFIXES.get();
		if (prefixes.length == 0) {
			return List.of();
		}
		return StackWalker.getInstance()
			.walk((frames) -> frames.limit(MAX_WALK)
				.filter((f) -> f.getLineNumber() > 0 && startsWithAny(f.getClassName(), prefixes))
				.limit(MAX_APP_FRAMES)
				.map((f) -> f.getClassName() + ":" + f.getLineNumber())
				.toList());
	}

	/** The anchor ({@code Class:line}) of a captured path, or {@code null} when empty. */
	public static String site(List<String> path) {
		return path.isEmpty() ? null : path.get(0);
	}

	/**
	 * The simple class name of the path's outermost app frame (the request entry), or
	 * {@code null} when there is no app caller above the anchor's own class — so the
	 * {@code ↳ under X} marker only appears when the op genuinely ran beneath another
	 * application frame.
	 */
	public static String entryClass(List<String> path) {
		if (path.size() < 2) {
			return null;
		}
		String outer = simpleClass(path.get(path.size() - 1));
		String anchor = simpleClass(path.get(0));
		return outer.equals(anchor) ? null : outer;
	}

	private static String simpleClass(String frame) {
		int colon = frame.indexOf(':');
		String type = (colon < 0) ? frame : frame.substring(0, colon);
		int dot = type.lastIndexOf('.');
		return (dot < 0) ? type : type.substring(dot + 1);
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
