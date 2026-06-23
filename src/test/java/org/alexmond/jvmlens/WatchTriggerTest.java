package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class WatchTriggerTest {

	private static ProfileSummary summary(long gcMs, double topCpuShare, long oldObjects) {
		List<Ranked> hot = (topCpuShare > 0) ? List.of(new Ranked("com.example.Svc.run", topCpuShare, null))
				: List.of();
		return new ProfileSummary("r.jfr", 1000, 0, oldObjects, 1, gcMs, hot, List.of(), List.of(), List.of(),
				List.of(), List.of(), "cause");
	}

	@Test
	void inactiveWhenNoThresholdsSet() {
		assertThat(new WatchTrigger(0, 0, 0).active()).isFalse();
		assertThat(new WatchTrigger(100, 0, 0).active()).isTrue();
	}

	@Test
	void gcThresholdFiresAtOrAboveLimit() {
		WatchTrigger t = new WatchTrigger(500, 0, 0);
		assertThat(t.reason(summary(600, 0, 0))).contains("GC pause 600ms");
		assertThat(t.reason(summary(400, 0, 0))).isNull();
	}

	@Test
	void cpuShareThresholdFiresOnDominantHotPath() {
		WatchTrigger t = new WatchTrigger(0, 0.40, 0);
		assertThat(t.reason(summary(0, 0.90, 0))).contains("hot path 90%");
		assertThat(t.reason(summary(0, 0.10, 0))).isNull();
		assertThat(t.reason(summary(0, 0, 0))).isNull(); // no hot paths at all
	}

	@Test
	void oldObjectsThresholdFiresOnRetainedSamples() {
		WatchTrigger t = new WatchTrigger(0, 0, 10);
		assertThat(t.reason(summary(0, 0, 12))).contains("12 old-object samples");
		assertThat(t.reason(summary(0, 0, 3))).isNull();
	}

	@Test
	void combinesMultipleBreachedReasons() {
		WatchTrigger t = new WatchTrigger(500, 0.40, 10);
		String reason = t.reason(summary(600, 0.90, 12));
		assertThat(reason).contains("GC pause").contains("hot path").contains("old-object");
	}

}
