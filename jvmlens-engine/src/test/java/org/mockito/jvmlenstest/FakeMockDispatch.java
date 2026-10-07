package org.mockito.jvmlenstest;

/**
 * Sits in the {@code org.mockito} package so a real recording carries mock-framework
 * frames without the engine taking a Mockito dependency — the test double for
 * {@code SummarizerTest}'s harness-dominated note (#163).
 */
public final class FakeMockDispatch {

	private FakeMockDispatch() {
	}

	public static double intercept(int rounds) {
		double x = 0;
		for (int i = 0; i < rounds; i++) {
			x += Math.sqrt(i);
		}
		return x;
	}

}
