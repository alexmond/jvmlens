package org.alexmond.jvmlens.moveapp;

import java.util.Map;

/**
 * The application code of the #190 move tests: {@link #invoke} does map lookups inline
 * (before an extract-method refactor), through {@link #find} (after it), or not at all
 * (the look-alike, where the lookups went elsewhere).
 */
public final class Caller {

	/** Lookups inline in {@link #invoke}. */
	public static final int INLINE = 0;

	/** Lookups extracted into {@link #find}. */
	public static final int EXTRACTED = 1;

	/** No lookups at all. */
	public static final int NONE = 2;

	private Caller() {
	}

	public static long invoke(Map<Integer, Integer> map, int rounds, int mode) {
		long acc = 0;
		for (int i = 0; i < rounds; i++) {
			int key = Keys.mix(i, map.size());
			acc += Keys.churn(key);
			if (mode == INLINE) {
				Integer v = map.get(key);
				acc += (v != null) ? v : 0;
			}
			else if (mode == EXTRACTED) {
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
