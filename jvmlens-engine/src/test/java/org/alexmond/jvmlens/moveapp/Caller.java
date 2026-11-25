package org.alexmond.jvmlens.moveapp;

import java.util.Map;

/**
 * The application code of the #190 move tests: {@link #invoke} does map lookups inline
 * (before an extract-method refactor), through {@link #find} (after it), or not at all
 * (the look-alike, where the lookups went elsewhere).
 *
 * <p>
 * The variant is a build constant, read once from {@value #MODE_PROPERTY} when the class
 * initialises, so each side of a test loads its own build of this package in its own
 * class loader, like two releases of the app. It used to be a runtime argument, which
 * gave one method — one profile, one compiled body — both variants: C2 then merged the
 * two identical lookups under one inlined scope, and JFR put an extracted run's samples
 * on the inline branch (#211). A {@code static final} folds the other variants away.
 */
public final class Caller {

	/** The system property naming the variant this build was loaded as. */
	public static final String MODE_PROPERTY = "jvmlens.moveapp.mode";

	/** Lookups inline in {@link #invoke}. */
	public static final int INLINE = 0;

	/** Lookups extracted into {@link #find}. */
	public static final int EXTRACTED = 1;

	/** No lookups at all. */
	public static final int NONE = 2;

	private static final int MODE = Integer.getInteger(MODE_PROPERTY, INLINE);

	private Caller() {
	}

	public static long invoke(Map<Integer, Integer> map, int rounds) {
		long acc = 0;
		for (int i = 0; i < rounds; i++) {
			int key = Keys.mix(i, map.size());
			acc += Keys.churn(key);
			if (MODE == INLINE) {
				Integer v = map.get(key);
				acc += (v != null) ? v : 0;
			}
			else if (MODE == EXTRACTED) {
				acc += find(map, key);
			}
		}
		return acc;
	}

	static long find(Map<Integer, Integer> map, int key) {
		Integer v = map.get(key);
		return (v != null) ? v : 0;
	}

}
