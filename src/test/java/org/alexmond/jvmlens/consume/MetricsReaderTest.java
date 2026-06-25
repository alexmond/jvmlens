package org.alexmond.jvmlens.consume;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the reflective Micrometer reader against a hand-built registry that mimics
 * the Micrometer API shape ({@code getMeters} → {@code getId().getName()}, {@code count},
 * {@code totalTime}), so no Micrometer dependency is needed on the test classpath.
 */
class MetricsReaderTest {

	@Test
	void nullOrEmptyRegistryYieldsNoSection() {
		assertThat(MetricsReader.read(null)).isEmpty();
		assertThat(MetricsReader.read(new FakeRegistry(List.of()))).isEmpty();
	}

	@Test
	void summarizesTimersRanksByTotalTimeAndSkipsNonTimers() {
		FakeRegistry registry = new FakeRegistry(List.of(new FakeTimer("http.server.requests", 100, 5_000_000_000L),
				new FakeTimer("jdbc.query", 400, 2_000_000_000L), new FakeGauge("jvm.memory.used")));
		List<Section> sections = MetricsReader.read(registry);
		assertThat(sections).hasSize(1);
		Section metrics = sections.get(0);
		assertThat(metrics.key()).isEqualTo("metrics");
		List<Ranked> rows = metrics.rows();
		assertThat(rows.get(0).name()).isEqualTo("http.server.requests");
		assertThat(rows.get(0).stack()).contains("100 calls").contains("avg 50.0 ms");
		// the gauge (no totalTime/count) is skipped, only the two timers remain
		assertThat(rows).hasSize(2).noneMatch((r) -> r.name().equals("jvm.memory.used"));
	}

	// --- Micrometer-shaped test doubles (read purely by reflection) ---

	public static final class FakeRegistry {

		private final List<?> meters;

		FakeRegistry(List<?> meters) {
			this.meters = meters;
		}

		public List<?> getMeters() {
			return this.meters;
		}

	}

	public static final class FakeId {

		private final String name;

		FakeId(String name) {
			this.name = name;
		}

		public String getName() {
			return this.name;
		}

	}

	public static final class FakeTimer {

		private final FakeId id;

		private final long count;

		private final long nanos;

		FakeTimer(String name, long count, long nanos) {
			this.id = new FakeId(name);
			this.count = count;
			this.nanos = nanos;
		}

		public FakeId getId() {
			return this.id;
		}

		public long count() {
			return this.count;
		}

		public double totalTime(TimeUnit unit) {
			return unit.convert(this.nanos, TimeUnit.NANOSECONDS);
		}

	}

	public static final class FakeGauge {

		private final FakeId id;

		FakeGauge(String name) {
			this.id = new FakeId(name);
		}

		public FakeId getId() {
			return this.id;
		}

	}

}
