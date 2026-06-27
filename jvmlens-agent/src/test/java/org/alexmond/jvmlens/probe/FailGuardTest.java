package org.alexmond.jvmlens.probe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FailGuardTest {

	@AfterEach
	void resetBreaker() {
		FailGuard.reset();
	}

	@Test
	void swallowsAnyThrowableIncludingError() {
		assertThatCode(() -> FailGuard.run("db", () -> {
			throw new StackOverflowError("boom"); // the exact #68 Bug 2 failure shape
		})).doesNotThrowAnyException();
	}

	@Test
	void runsTheBodyOnSuccess() {
		int[] ran = { 0 };
		FailGuard.run("db", () -> ran[0]++);
		assertThat(ran[0]).isEqualTo(1);
	}

	@Test
	void disablesADimensionAfterRepeatedFailuresAndIsolatesOthers() {
		int[] calls = { 0 };
		for (int i = 0; i < 10; i++) {
			FailGuard.run("web", () -> {
				calls[0]++;
				throw new IllegalStateException("x");
			});
		}
		// the breaker stops invoking the body once the dimension is disabled (after 5)
		assertThat(calls[0]).isEqualTo(5);
		// a different dimension is unaffected by web's breaker
		int[] other = { 0 };
		FailGuard.run("db", () -> other[0]++);
		assertThat(other[0]).isEqualTo(1);
	}

}
