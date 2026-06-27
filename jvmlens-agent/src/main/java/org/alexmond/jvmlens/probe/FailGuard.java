package org.alexmond.jvmlens.probe;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fail-open guard for instrumentation capture. Runs a capture body and, if it throws
 * <em>anything</em> — including {@link Error} / {@code StackOverflowError} — swallows it
 * so the host application is never affected, then counts the failure per dimension and,
 * after a few failures, disables that dimension for the rest of the run (logging once). A
 * monitoring agent must degrade monitoring, not crash the app it observes — the #68 Bug 2
 * incident (a sanitizer regex overflow that crash-looped the host at EMF build) is
 * exactly what this prevents. The {@code suppress = Throwable.class} on the advice is the
 * bytecode backstop; this adds the per-dimension circuit breaker (field-finding #73).
 */
public final class FailGuard {

	/** Failures in one dimension before it is disabled for the run. */
	private static final int MAX_FAILURES = 5;

	private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();

	private static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();

	private FailGuard() {
	}

	/**
	 * Run a capture body fail-open under the named dimension's circuit breaker.
	 * @param dimension the instrumentation dimension (e.g. {@code db}, {@code web})
	 * @param body the capture work; any throwable is contained
	 */
	@SuppressWarnings("PMD.AvoidCatchingThrowable") // fail-open: a profiler must never
													// crash the host
	public static void run(String dimension, Body body) {
		if (DISABLED.contains(dimension)) {
			return;
		}
		try {
			body.run();
		}
		catch (Throwable failure) { // incl. Error / StackOverflowError, by design
			if (FAILURES.merge(dimension, 1, Integer::sum) >= MAX_FAILURES && DISABLED.add(dimension)) {
				System.err.println("jvmlens-agent: '" + dimension + "' instrumentation disabled after " + MAX_FAILURES
						+ " failures (" + failure.getClass().getName() + "); host unaffected");
			}
		}
	}

	/** Reset the breaker state (tests). */
	public static void reset() {
		FAILURES.clear();
		DISABLED.clear();
	}

	/** A capture body; may throw anything (it is fully contained by {@link #run}). */
	@FunctionalInterface
	public interface Body {

		void run();

	}

}
