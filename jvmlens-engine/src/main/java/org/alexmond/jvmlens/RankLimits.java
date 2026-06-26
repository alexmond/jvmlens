package org.alexmond.jvmlens;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-adjustable "top-N" — how many ranked rows each section keeps. Default is the
 * same for every category; an operator can raise or lower it globally ({@code all}) or
 * per category ({@code cpu}, {@code memory}, {@code locks}, {@code io}, {@code db},
 * {@code web}, …) via the agent control plane, so a summary can be widened for memory
 * while staying tight for everything else. A per-category value wins over {@code all},
 * which wins over the built-in default.
 */
public final class RankLimits {

	/** The built-in top-N when nothing is overridden. */
	public static final int DEFAULT = 5;

	private static final String ALL = "all";

	private static final Map<String, Integer> OVERRIDES = new ConcurrentHashMap<>();

	private RankLimits() {
	}

	/**
	 * The effective top-N for a category (its own override, else {@code all}, else
	 * default).
	 */
	public static int limit(String category) {
		Integer specific = OVERRIDES.get(category);
		if (specific != null) {
			return specific;
		}
		return OVERRIDES.getOrDefault(ALL, DEFAULT);
	}

	/** Set the top-N for a category ({@code all} for the global fallback). */
	public static void set(String category, int n) {
		OVERRIDES.put(category, Math.max(n, 1));
	}

	/** Clear all overrides (back to {@link #DEFAULT}). */
	public static void reset() {
		OVERRIDES.clear();
	}

	/** A compact description of the current limits, e.g. {@code default=5, memory=10}. */
	public static String describe() {
		StringBuilder b = new StringBuilder("default=").append(OVERRIDES.getOrDefault(ALL, DEFAULT));
		OVERRIDES.forEach((category, n) -> {
			if (!ALL.equals(category)) {
				b.append(", ").append(category).append('=').append(n);
			}
		});
		return b.toString();
	}

}
