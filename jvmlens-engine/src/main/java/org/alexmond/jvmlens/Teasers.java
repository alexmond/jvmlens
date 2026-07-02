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
	 * The synthetic "Top allocated types" row that collapses every type excluded by
	 * {@code -x} into one line (#128). The bytes stay accounted (folded, not dropped) so
	 * the app-attributable types stand out without hiding the total.
	 */
	static final String EXCLUDED_TYPES_LABEL = "«excluded types (-x), rolled up»";

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

	/**
	 * A compact, readable type name: package stripped and JVM array descriptors decoded —
	 * {@code java.lang.String} → {@code String}, {@code [B} → {@code byte[]},
	 * {@code [Ljava.lang.String;} → {@code String[]}.
	 */
	static String simpleType(String type) {
		int dims = 0;
		while (dims < type.length() && type.charAt(dims) == '[') {
			dims++;
		}
		String base = (dims == 0) ? type : arrayBase(type.substring(dims));
		int dot = base.lastIndexOf('.');
		String simple = (dot >= 0) ? base.substring(dot + 1) : base;
		return simple + "[]".repeat(dims);
	}

	/**
	 * Fold every allocated type whose element package matches an explicit {@code -x}
	 * exclude into one rolled-up {@link #EXCLUDED_TYPES_LABEL} row, so app-attributable
	 * types aren't crowded out by embedded infrastructure (e.g. an in-process H2's
	 * MVStore types on a test capture). The bytes stay accounted, not dropped. The
	 * exclude prefixes are the same {@code -x} that already scopes the hot-path and
	 * allocation-site blocks; an empty exclude list leaves the map untouched (#128).
	 */
	static Map<String, Long> foldExcludedTypes(Map<String, Long> byType, List<String> excludes) {
		if (excludes.isEmpty()) {
			return byType;
		}
		Map<String, Long> out = new java.util.HashMap<>();
		long folded = 0;
		for (Map.Entry<String, Long> e : byType.entrySet()) {
			if (startsWithAnyPrefix(baseTypeName(e.getKey()), excludes)) {
				folded += e.getValue();
			}
			else {
				out.put(e.getKey(), e.getValue());
			}
		}
		if (folded > 0) {
			out.merge(EXCLUDED_TYPES_LABEL, folded, Long::sum);
		}
		return out;
	}

	/**
	 * The fully-qualified element type of a possibly-array type — the package is retained
	 * (unlike {@link #simpleType}) so an exclude prefix can match it. {@code
	 * [Lorg.h2.mvstore.Page$PageReference;} → {@code org.h2.mvstore.Page$PageReference};
	 * {@code [B} → {@code byte}; a scalar type is returned unchanged.
	 */
	private static String baseTypeName(String type) {
		int dims = 0;
		while (dims < type.length() && type.charAt(dims) == '[') {
			dims++;
		}
		return (dims == 0) ? type : arrayBase(type.substring(dims));
	}

	private static boolean startsWithAnyPrefix(String value, List<String> prefixes) {
		for (String p : prefixes) {
			if (value.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	/** The element type of a JVM array descriptor (e.g. {@code B} → {@code byte}). */
	private static String arrayBase(String descriptor) {
		return switch (descriptor.isEmpty() ? ' ' : descriptor.charAt(0)) {
			case 'B' -> "byte";
			case 'S' -> "short";
			case 'I' -> "int";
			case 'J' -> "long";
			case 'F' -> "float";
			case 'D' -> "double";
			case 'C' -> "char";
			case 'Z' -> "boolean";
			case 'L' -> descriptor.substring(1, descriptor.length() - 1);
			default -> descriptor;
		};
	}

}
