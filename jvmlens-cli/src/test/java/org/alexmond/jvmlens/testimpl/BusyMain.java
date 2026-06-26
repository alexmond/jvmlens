package org.alexmond.jvmlens.testimpl;

/**
 * A CPU-busy {@code main} used as a live attach target by {@code ProfileCommandTest}.
 * Runs a hot loop long enough for a short JFR capture to collect execution samples, then
 * exits.
 */
public final class BusyMain {

	private BusyMain() {
	}

	public static void main(String[] args) {
		long end = System.nanoTime() + 30_000_000_000L;
		double x = 0;
		while (System.nanoTime() < end) {
			for (int i = 0; i < 100_000; i++) {
				x += Math.sqrt(i);
			}
		}
		if (x < 0) {
			throw new IllegalStateException("unreachable");
		}
	}

}
