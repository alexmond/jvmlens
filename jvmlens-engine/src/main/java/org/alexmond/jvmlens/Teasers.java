package org.alexmond.jvmlens;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small confidence/teaser heuristics shared by the {@link Summarizer} rendering — the
 * leaf-distribution "where time actually goes" teaser (#53 item 3) and the
 * escape-analysis "may be a false lever" check on allocation sites (#103). Pure functions
 * over already-aggregated data; kept out of {@code Summarizer} so that class stays
 * focused on the JFR reduction.
 */
final class Teasers {

	/**
	 * How many leaves to list per hot path in its leaf-distribution teaser (#53 item 3).
	 */
	static final int LEAF_TEASER_COUNT = 3;

	/**
	 * A path's top leaf must hold this share or the teaser is flagged diffuse (#53 item
	 * 3).
	 */
	static final double LEAF_CONFIDENCE = 0.20;

	/**
	 * Boxed primitives C2 can scalar-replace when non-escaping — see
	 * {@link #escapeProneType}.
	 */
	private static final Set<String> BOXED_PRIMITIVES = Set.of("java.lang.Integer", "java.lang.Long",
			"java.lang.Double", "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean",
			"java.lang.Float");

	private Teasers() {
	}

	/**
	 * The top {@value #LEAF_TEASER_COUNT} leaves of one hot path, formatted
	 * {@code leaf c/total · …} — where the path's time actually goes. Flagged
	 * {@code diffuse} when no single leaf holds {@value #LEAF_CONFIDENCE} of the path, so
	 * the reader doesn't chase a 2/168 frame (#53 item 3).
	 */
	static String leafBreakdown(Map<String, Long> byLeaf, long pathTotal) {
		List<Map.Entry<String, Long>> top = byLeaf.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(LEAF_TEASER_COUNT)
			.toList();
		String leaves = top.stream()
			.map((l) -> l.getKey() + " " + l.getValue() + "/" + pathTotal)
			.collect(java.util.stream.Collectors.joining(" · "));
		boolean diffuse = pathTotal <= 0 || top.isEmpty()
				|| (double) top.get(0).getValue() / pathTotal < LEAF_CONFIDENCE;
		return diffuse ? leaves + " ⚠ diffuse — no leaf >" + (int) (LEAF_CONFIDENCE * 100) + "% of path" : leaves;
	}

	/**
	 * A non-escaping-allocation candidate C2 can scalar-replace — a boxed primitive or a
	 * captured lambda. A hot alloc site dominated by one may vanish in steady state, so
	 * its sampled bytes can be a false lever (#103); the renderer hedges it. Verify with
	 * {@code -prof gc}.
	 */
	static boolean escapeProneType(String type) {
		return type != null && (type.contains("$$Lambda") || BOXED_PRIMITIVES.contains(type));
	}

}
