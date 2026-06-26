package org.alexmond.jvmlens.consume;

import java.util.List;

import org.alexmond.jvmlens.ProfileSummary.Section;

/**
 * Bridges {@link MetricsReader} to the live Micrometer global registry by reflection:
 * fetches {@code io.micrometer.core.instrument.Metrics.globalRegistry} if Micrometer is
 * on the target's classpath, and returns its summarized timers — otherwise an empty list
 * (detect-and-degrade, no dependency). Glue only; the reading logic is in
 * {@link MetricsReader}.
 */
public final class MicrometerSource {

	private MicrometerSource() {
	}

	/**
	 * Summarize the Micrometer global registry's timers, or empty if it isn't present.
	 */
	public static List<Section> readGlobal() {
		try {
			Class<?> metrics = Class.forName("io.micrometer.core.instrument.Metrics");
			Object registry = metrics.getField("globalRegistry").get(null);
			return MetricsReader.read(registry);
		}
		catch (Exception ignored) {
			return List.of();
		}
	}

}
