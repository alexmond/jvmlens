package org.alexmond.jvmlens.consume;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

/**
 * Consumes an existing <a href="https://micrometer.io">Micrometer</a>
 * {@code MeterRegistry} (e.g. the one Spring Boot Actuator already populates) and
 * summarizes its <em>timers</em> — {@code http.server.requests}, {@code jdbc},
 * {@code cache}, … — as the {@code metrics} extended section, instead of re-instrumenting
 * what the app already measures.
 *
 * <p>
 * The registry is read entirely by <em>reflection</em> against the stable Micrometer API
 * ({@code getMeters} → {@code getId().getName()}, {@code count()},
 * {@code totalTime(unit)}), so jvmlens keeps no Micrometer dependency and degrades to
 * empty when a meter isn't a timer or the API differs.
 */
public final class MetricsReader {

	/** How many timers the section keeps. */
	private static final int TOP_N = 5;

	private MetricsReader() {
	}

	/**
	 * Summarize a Micrometer registry's timers as the {@code metrics} section.
	 * @param registry a Micrometer {@code MeterRegistry} (read reflectively); may be
	 * {@code null}
	 * @return a single-element list, or empty when there is nothing to read
	 */
	public static List<Section> read(Object registry) {
		if (registry == null) {
			return List.of();
		}
		Map<String, Long> byName = new HashMap<>();
		Map<String, Long> counts = new HashMap<>();
		try {
			Object meters = registry.getClass().getMethod("getMeters").invoke(registry);
			if (meters instanceof Iterable<?> iterable) {
				for (Object meter : iterable) {
					readTimer(meter, byName, counts);
				}
			}
		}
		catch (Exception ex) {
			return List.of();
		}
		if (byName.isEmpty()) {
			return List.of();
		}
		long total = byName.values().stream().mapToLong(Long::longValue).sum();
		List<Ranked> rows = byName.entrySet()
			.stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.limit(TOP_N)
			.map((en) -> new Ranked(en.getKey(), (total > 0) ? (double) en.getValue() / total : 0, en.getValue(),
					teaser(counts.get(en.getKey()), en.getValue())))
			.toList();
		return List.of(new Section("metrics", "Top Micrometer timers (by total time)", "ms", true, rows));
	}

	private static void readTimer(Object meter, Map<String, Long> byName, Map<String, Long> counts) {
		try {
			long nanos = ((Number) meter.getClass()
				.getMethod("totalTime", TimeUnit.class)
				.invoke(meter, TimeUnit.NANOSECONDS)).longValue();
			long count = ((Number) meter.getClass().getMethod("count").invoke(meter)).longValue();
			Object id = meter.getClass().getMethod("getId").invoke(meter);
			String name = (String) id.getClass().getMethod("getName").invoke(id);
			byName.merge(name, nanos, Long::sum);
			counts.merge(name, count, Long::sum);
		}
		catch (Exception ignored) {
			// not a Timer (no totalTime/count) — skip
		}
	}

	private static String teaser(Long count, long nanos) {
		long c = (count != null) ? count : 0;
		double avgMs = (c > 0) ? (nanos / 1_000_000.0 / c) : 0;
		return String.format(java.util.Locale.ROOT, "%d calls, avg %.1f ms", c, avgMs);
	}

}
