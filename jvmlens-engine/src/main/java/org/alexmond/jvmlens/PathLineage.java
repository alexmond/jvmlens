package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

import org.alexmond.jvmlens.ProfileSummary.Lineage;
import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * Collects each hot path's {@link Lineage} — which application frame calls it, and which
 * frames it calls into — so a diff can tell an extract-method refactor from new work
 * (#190). Structured data only: nothing here is rendered in a summary.
 *
 * <p>
 * Callees are counted <em>inclusively</em> (every frame between the leaf and the
 * application frame, once per sample), not by leaf: inside an inlined chain the sampled
 * leaf is a JIT artefact — the same {@code HashMap.get} lookup lands on
 * {@code Integer.equals} in one compile and {@code HashMap.getNode} in the next — while
 * the outer callee is on every one of those stacks.
 */
final class PathLineage {

	/** Per application frame: its nearest application caller → samples. */
	private final Map<String, Map<String, Long>> callersByApp = new HashMap<>();

	/** Per application frame: each frame it calls into (inclusive) → samples. */
	private final Map<String, Map<String, Long>> calleesByApp = new HashMap<>();

	/**
	 * Count one sample of application frame {@code app}: every distinct frame above it
	 * (towards the leaf) as a callee — or {@code app} itself when it is the leaf, its
	 * self time — and its caller, the next application frame below it that is a different
	 * method. A sample with no such frame counts no caller.
	 */
	void add(String app, RecordedStackTrace stack, Scope scope) {
		Map<String, Long> callees = this.calleesByApp.computeIfAbsent(app, (k) -> new HashMap<>());
		Set<String> seen = new HashSet<>();
		boolean pastApp = false;
		for (RecordedFrame frame : stack.getFrames()) {
			if (!frame.isJavaFrame() || frame.getMethod() == null) {
				continue;
			}
			String key = Teasers.frameKey(frame);
			boolean application = scope.isApplication(frame.getMethod().getType().getName());
			if (!pastApp && !application) {
				if (seen.add(key)) {
					callees.merge(key, 1L, Long::sum);
				}
				continue;
			}
			if (!pastApp) {
				pastApp = true;
				if (seen.isEmpty()) {
					callees.merge(app, 1L, Long::sum); // the app frame is the leaf
				}
				continue;
			}
			if (application && !key.equals(app)) {
				this.callersByApp.computeIfAbsent(app, (k) -> new HashMap<>()).merge(key, 1L, Long::sum);
				return;
			}
		}
	}

	/**
	 * The hot-path {@code rows} with their lineage attached.
	 * @param rows the ranked hot paths
	 * @return the same rows, in order, each carrying a {@link Lineage}
	 */
	List<Ranked> attach(List<Ranked> rows) {
		List<Ranked> out = new ArrayList<>(rows.size());
		for (Ranked r : rows) {
			Lineage lineage = new Lineage(this.callersByApp.getOrDefault(r.name(), Map.of()),
					this.calleesByApp.getOrDefault(r.name(), Map.of()));
			out.add(new Ranked(r.name(), r.share(), r.count(), r.stack(), lineage));
		}
		return out;
	}

}
