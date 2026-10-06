package org.alexmond.jvmlens.viaapp;

import org.alexmond.jvmlens.vialib.Resolver;

/** The application frame of the #162 "via" test — it only calls into the library. */
public final class Entry {

	private Entry() {
	}

	public static int render(int rounds) {
		return Resolver.resolve(rounds);
	}

}
