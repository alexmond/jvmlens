package org.alexmond.jvmlens;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

/**
 * Finds the "via" frame of a hot path: the library frame between the application frame
 * and the leaves that owns most of the path's samples. The digest names the app entry and
 * the JDK leaf, but the lever is often one frame in between — a library method that
 * funnels the time into the JDK ({@code ognl.OgnlRuntime.getReadMethod} calling
 * {@code Class.getMethods()} on every read, field-finding #162). Naming it saves a manual
 * {@code jfr print} group-by.
 *
 * <p>
 * Counts are <em>inclusive</em>: every non-runtime frame below the app frame is counted
 * once per sample. Of the frames that own at least {@value #OWNS} of the path, the one
 * closest to the leaves wins — the last point the time funnels through.
 */
final class ViaFrames {

	/** An inner frame must hold this share of the path's samples to be named. */
	static final double OWNS = 0.5;

	/** Below this many samples a path is too small to judge. */
	static final long MIN_PATH_SAMPLES = 20;

	/** Per application frame: inner frame → {samples, summed distance from the leaf}. */
	private final Map<String, Map<String, long[]>> byApp = new HashMap<>();

	/**
	 * Count the frames of one sample that lie below its application frame {@code app}.
	 * JDK/runtime and native frames are skipped — a via must be code someone can change
	 * or work around.
	 */
	void add(String app, RecordedStackTrace stack, Scope scope) {
		Set<String> seen = new HashSet<>();
		int depth = 0;
		for (RecordedFrame frame : stack.getFrames()) {
			if (frame.isJavaFrame() && frame.getMethod() != null) {
				String owner = frame.getMethod().getType().getName();
				if (scope.isApplication(owner)) {
					return;
				}
				if (!Scope.isRuntime(owner) && seen.add(Teasers.frameKey(frame))) {
					count(app, Teasers.frameKey(frame), depth);
				}
			}
			depth++;
		}
	}

	void count(String app, String frame, int depth) {
		long[] tally = this.byApp.computeIfAbsent(app, (k) -> new HashMap<>())
			.computeIfAbsent(frame, (k) -> new long[2]);
		tally[0]++;
		tally[1] += depth;
	}

	/**
	 * The teaser suffix naming {@code app}'s via frame —
	 * {@code " · mostly via X n/total"} — or empty when no inner frame owns most of the
	 * path. A frame that is only ever the leaf is left out: the leaf list
	 * ({@code byLeaf}) already shows it.
	 */
	String teaser(String app, long pathTotal, Map<String, Long> byLeaf) {
		Map<String, long[]> frames = this.byApp.get(app);
		if (frames == null || pathTotal < MIN_PATH_SAMPLES) {
			return "";
		}
		String via = null;
		long viaCount = 0;
		double viaDepth = Double.MAX_VALUE;
		for (Map.Entry<String, long[]> e : frames.entrySet()) {
			long count = e.getValue()[0];
			double depth = (double) e.getValue()[1] / count;
			boolean onlyALeaf = count == byLeaf.getOrDefault(e.getKey(), 0L);
			if (count >= pathTotal * OWNS && !onlyALeaf && depth < viaDepth) {
				via = e.getKey();
				viaCount = count;
				viaDepth = depth;
			}
		}
		return (via != null) ? " · mostly via " + via + " " + viaCount + "/" + pathTotal : "";
	}

}
