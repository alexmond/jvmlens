package org.alexmond.jvmlens.vialib;

import java.util.Locale;

/**
 * Stands in for a third-party library sitting between app code and a JDK leaf — the
 * {@code ognl.OgnlRuntime.getReadMethod} of field-finding #162. Its cost is all in the
 * JDK ({@code toLowerCase}), so only a "via" frame names it.
 */
public final class Resolver {

	private static final String NAME = "GetSomePropertyNameThatIsLongEnoughToCost".repeat(40);

	private Resolver() {
	}

	public static int resolve(int rounds) {
		int hits = 0;
		for (int i = 0; i < rounds; i++) {
			hits += NAME.toLowerCase(Locale.ROOT).length();
		}
		return hits;
	}

}
