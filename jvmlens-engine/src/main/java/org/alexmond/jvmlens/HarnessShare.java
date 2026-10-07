package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

/**
 * Tracks how much of a profile runs inside a mock framework, so a test-harness-dominated
 * capture is called out instead of read as the application's own cost. Profiling a JUnit
 * benchmark that drives Mockito-mocked collaborators showed the mock machinery honestly
 * ({@code TypeSafeMatching} leaves, gigabytes of {@code StackFrameInfo}) but never said
 * so — it cost a round to realise the numbers described the harness (field-finding #163).
 *
 * <p>
 * A sample counts when <em>any</em> frame of its stack belongs to a mock library. Only
 * mock libraries count: test-runner frames sit under every sample of a test run, and
 * ByteBuddy alone also serves Hibernate and agents in production.
 */
final class HarnessShare {

	/** The share of CPU samples or allocation that must run under mocks to be noted. */
	static final double DOMINATES = 0.20;

	/** Below this many execution samples a CPU share is too noisy to judge. */
	static final long MIN_EXEC_SAMPLES = 50;

	private static final List<String> MOCK_LIBRARIES = List.of("org.mockito.", "org.easymock.", "org.powermock.",
			"org.jmock.", "io.mockk.");

	private String library;

	private long execSamples;

	private long allocBytes;

	/** Count an execution or allocation sample if it ran under a mock framework. */
	void add(RecordedEvent event) {
		String type = event.getEventType().getName();
		boolean exec = "jdk.ExecutionSample".equals(type);
		RecordedStackTrace stack = event.getStackTrace();
		if (stack == null || (!exec && !"jdk.ObjectAllocationSample".equals(type))) {
			return;
		}
		List<String> owners = new ArrayList<>();
		for (RecordedFrame frame : stack.getFrames()) {
			if (frame.isJavaFrame() && frame.getMethod() != null) {
				owners.add(frame.getMethod().getType().getName());
			}
		}
		String mock = mockLibrary(owners);
		if (mock != null) {
			count(mock, exec ? 1 : 0, (!exec && event.hasField("weight")) ? event.getLong("weight") : 0);
		}
	}

	void count(String mockLibrary, long execSamples, long allocBytes) {
		this.library = (this.library != null) ? this.library : mockLibrary;
		this.execSamples += execSamples;
		this.allocBytes += allocBytes;
	}

	/** The mock library owning any of {@code owners} (frame type names), or null. */
	static String mockLibrary(List<String> owners) {
		for (String owner : owners) {
			for (String mock : MOCK_LIBRARIES) {
				if (owner.startsWith(mock)) {
					return mock;
				}
			}
		}
		return null;
	}

	/**
	 * A hedged note for the suspected cause — or empty — when at least
	 * {@value #DOMINATES} of the CPU samples or allocation ran under a mock framework.
	 */
	String note(long execTotal, long allocTotal) {
		List<String> parts = new ArrayList<>();
		if (execTotal >= MIN_EXEC_SAMPLES && this.execSamples >= execTotal * DOMINATES) {
			parts.add(percent(this.execSamples, execTotal) + " of CPU samples");
		}
		if (allocTotal > 0 && this.allocBytes >= allocTotal * DOMINATES) {
			parts.add(percent(this.allocBytes, allocTotal) + " of allocation");
		}
		if (parts.isEmpty()) {
			return "";
		}
		String name = this.library.substring(0, this.library.length() - 1);
		return " ⚠ Looks test-harness dominated — " + String.join(" and ", parts) + " ran inside a mock framework ("
				+ name + "), so these numbers describe the harness more than the code under test; "
				+ "a plain driver (`bench --main`) gives representative ones.";
	}

	private static String percent(long part, long total) {
		return String.format(Locale.ROOT, "%.0f%%", 100.0 * part / total);
	}

}
