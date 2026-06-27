package org.alexmond.jvmlens.testimpl;

/**
 * A short CPU workload whose {@code main} returns quickly — one bench iteration for
 * {@code BenchCommandTest}. Unlike {@code BusyMain} (which spins for ~30s as a live
 * attach target), this does a bounded burst so a warmup+timed loop finishes in well under
 * a second.
 */
public final class BenchWorkload {

	private BenchWorkload() {
	}

	public static void main(String[] args) {
		double x = 0;
		for (int i = 0; i < 500_000; i++) {
			x += Math.sqrt(i);
		}
		if (x < 0) {
			throw new IllegalStateException("unreachable");
		}
	}

}
