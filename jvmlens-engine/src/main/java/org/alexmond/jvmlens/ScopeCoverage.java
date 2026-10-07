package org.alexmond.jvmlens;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;

/**
 * Notices when the scope hides most of a profile. A CPU sample with no application frame
 * is attributed to no hot path, so a scope that does not match the code being profiled —
 * someone profiling a library on the default not-application list, or a mistyped
 * {@code -a} — used to print empty hot paths and say nothing. This names the package that
 * actually holds those samples and the {@code -a} to pass.
 */
final class ScopeCoverage {

	/** The share of CPU samples that must be unattributed before it is worth a note. */
	static final double HIDDEN = 0.5;

	/** One package must hold this share of all CPU samples to be suggested. */
	static final double OWNS = 0.2;

	/** Below this many execution samples the shares are too noisy to judge. */
	static final long MIN_EXEC_SAMPLES = 50;

	private final Scope scope;

	private final Map<String, Long> byPackage = new HashMap<>();

	private long unattributed;

	ScopeCoverage(Scope scope) {
		this.scope = scope;
	}

	/** Count an execution sample that has no application frame under the scope. */
	void add(RecordedEvent event) {
		if (!"jdk.ExecutionSample".equals(event.getEventType().getName()) || event.getStackTrace() == null) {
			return;
		}
		String library = null;
		for (RecordedFrame frame : event.getStackTrace().getFrames()) {
			if (frame.isJavaFrame() && frame.getMethod() != null) {
				String owner = frame.getMethod().getType().getName();
				if (this.scope.isApplication(owner)) {
					return;
				}
				if (library == null && !Scope.isRuntime(owner)) {
					library = suggestion(owner);
				}
			}
		}
		count(library, 1);
	}

	/**
	 * Record {@code samples} with no application frame; {@code libraryPackage} is the
	 * package of their nearest non-runtime frame, or null when they ran in runtime code.
	 */
	void count(String libraryPackage, long samples) {
		this.unattributed += samples;
		if (libraryPackage != null) {
			this.byPackage.merge(libraryPackage, samples, Long::sum);
		}
	}

	/**
	 * The package prefix to suggest for {@code -a}: the first two segments of the type's
	 * package (one, for a single-segment package), or null for the default package.
	 */
	static String suggestion(String owner) {
		int lastDot = owner.lastIndexOf('.');
		if (lastDot < 0) {
			return null;
		}
		String pkg = owner.substring(0, lastDot);
		int second = pkg.indexOf('.', pkg.indexOf('.') + 1);
		return (pkg.indexOf('.') < 0 || second < 0) ? pkg : pkg.substring(0, second);
	}

	/**
	 * A hedged note for the suspected cause, or empty when the scope covers the profile.
	 */
	String note(long execTotal) {
		if (execTotal < MIN_EXEC_SAMPLES || this.unattributed < execTotal * HIDDEN) {
			return "";
		}
		Map.Entry<String, Long> top = this.byPackage.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
		if (top == null || top.getValue() < execTotal * OWNS) {
			return "";
		}
		return String.format(Locale.ROOT,
				" ⚠ %.0f%% of CPU samples have no application frame under this scope, so they appear in no hot "
						+ "path — most of them run in `%s.*` (%d samples). To attribute them, pass `-a %s`.",
				100.0 * this.unattributed / execTotal, top.getKey(), top.getValue(), top.getKey());
	}

}
