package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

import org.alexmond.jvmlens.ProfileSummary.Lineage;
import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * Collects each hot path's {@link Lineage} — which application frame calls it, and which
 * leaves its samples end in — so a diff can tell an extract-method refactor from new work
 * (#190). Structured data only: nothing here is rendered in a summary.
 */
final class PathLineage {

	/** Per application frame: its nearest application caller → samples. */
	private final Map<String, Map<String, Long>> callersByApp = new HashMap<>();

	/**
	 * Count the caller of one sample's application frame {@code app}: the next
	 * application frame below it (towards the thread root) that is a different method. A
	 * sample with no such frame counts no caller.
	 */
	void add(String app, RecordedStackTrace stack, Scope scope) {
		boolean pastApp = false;
		for (RecordedFrame frame : stack.getFrames()) {
			if (!frame.isJavaFrame() || frame.getMethod() == null
					|| !scope.isApplication(frame.getMethod().getType().getName())) {
				continue;
			}
			String key = Teasers.frameKey(frame);
			if (pastApp && !key.equals(app)) {
				this.callersByApp.computeIfAbsent(app, (k) -> new HashMap<>()).merge(key, 1L, Long::sum);
				return;
			}
			pastApp = true;
		}
	}

	/**
	 * The hot-path {@code rows} with their lineage attached.
	 * @param rows the ranked hot paths
	 * @param leavesByApp per application frame: leaf → samples
	 * @param withLines labels each leaf with its dominant line
	 * @return the same rows, in order, each carrying a {@link Lineage}
	 */
	List<Ranked> attach(List<Ranked> rows, Map<String, Map<String, Long>> leavesByApp,
			UnaryOperator<Map<String, Long>> withLines) {
		List<Ranked> out = new ArrayList<>(rows.size());
		for (Ranked r : rows) {
			Lineage lineage = new Lineage(this.callersByApp.getOrDefault(r.name(), Map.of()),
					withLines.apply(leavesByApp.getOrDefault(r.name(), Map.of())));
			out.add(new Ranked(r.name(), r.share(), r.count(), r.stack(), lineage));
		}
		return out;
	}

}
