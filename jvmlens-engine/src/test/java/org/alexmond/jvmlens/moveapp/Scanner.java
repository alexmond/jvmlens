package org.alexmond.jvmlens.moveapp;

import java.util.Map;

/**
 * The #190 look-alike: genuinely new code doing the same map lookups as
 * {@link Caller#invoke} used to — same leaf, but {@code Caller} never calls it.
 */
public final class Scanner {

	private Scanner() {
	}

	public static long scan(Map<Integer, Integer> map, int rounds) {
		long acc = 0;
		for (int i = 0; i < rounds; i++) {
			Integer v = map.get(Keys.mix(i, map.size()));
			acc += (v != null) ? v : 0;
		}
		return acc;
	}

}
