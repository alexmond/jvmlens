package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

/**
 * Recognises a recording taken from a test run, and tracks how much of it runs inside a
 * mock framework, so a test-harness-dominated capture is called out instead of read as
 * the application's own cost. Profiling a JUnit benchmark that drives Mockito-mocked
 * collaborators showed the mock machinery honestly ({@code TypeSafeMatching} leaves,
 * gigabytes of {@code StackFrameInfo}) but never said so — it cost a round to realise the
 * numbers described the harness (field-finding #163).
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

	private static final List<String> TEST_RUNNERS = List.of("org.junit.", "org.testng.", "org.spockframework.",
			"io.kotest.");

	/** Runner frames must sit under this share of CPU samples to mark a test run. */
	static final double RUNS_UNDER = 0.50;

	private String library;

	private String launcher;

	private String runner;

	private long runnerSamples;

	private long execSamples;

	private long allocBytes;

	/** Count an execution or allocation sample if it ran under a mock framework. */
	void add(RecordedEvent event) {
		String type = event.getEventType().getName();
		if ("jdk.JVMInformation".equals(type)) {
			launchedBy(event.hasField("javaArguments") ? event.getString("javaArguments") : null);
			return;
		}
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
		String testRunner = exec ? runnerLibrary(owners) : null;
		if (testRunner != null) {
			countRunner(testRunner, 1);
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

	/** Record the JVM's launch command; a known test launcher marks a test run. */
	void launchedBy(String javaArguments) {
		String found = testLauncher(javaArguments);
		this.launcher = (found != null) ? found : this.launcher;
	}

	void countRunner(String runnerLibrary, long execSamples) {
		this.runner = (this.runner != null) ? this.runner : runnerLibrary;
		this.runnerSamples += execSamples;
	}

	/**
	 * The test launcher a JVM was started by, read from its launch command
	 * ({@code jdk.JVMInformation.javaArguments}), or null. This is the reliable signal:
	 * runner frames sit at the bottom of the stack, the first thing JFR's depth limit
	 * cuts off a deep test stack.
	 */
	static String testLauncher(String javaArguments) {
		if (javaArguments == null) {
			return null;
		}
		if (javaArguments.contains("surefire")) {
			return "Maven Surefire";
		}
		if (javaArguments.contains("GradleWorkerMain") && javaArguments.contains("Test Executor")) {
			return "Gradle test worker";
		}
		if (javaArguments.contains("JUnitStarter") || javaArguments.contains("org.eclipse.jdt.internal.junit")) {
			return "IDE JUnit runner";
		}
		if (javaArguments.contains("org.junit.platform.console")) {
			return "JUnit console launcher";
		}
		return javaArguments.contains("org.testng.") ? "TestNG" : null;
	}

	/**
	 * The test-runner library owning any of {@code owners} (frame type names), or null.
	 */
	static String runnerLibrary(List<String> owners) {
		return firstOwned(owners, TEST_RUNNERS);
	}

	/** The mock library owning any of {@code owners} (frame type names), or null. */
	static String mockLibrary(List<String> owners) {
		return firstOwned(owners, MOCK_LIBRARIES);
	}

	private static String firstOwned(List<String> owners, List<String> libraries) {
		for (String owner : owners) {
			for (String library : libraries) {
				if (owner.startsWith(library)) {
					return library;
				}
			}
		}
		return null;
	}

	/**
	 * Hedged trust notes, or empty. A recording taken from a test JVM says so; and when
	 * at least {@value #DOMINATES} of the CPU samples or allocation ran under a mock
	 * framework, it adds that the harness dominates.
	 */
	List<String> notes(long execTotal, long allocTotal) {
		List<String> notes = new ArrayList<>();
		for (String note : List.of(testRunNote(execTotal), mockNote(execTotal, allocTotal))) {
			if (!note.isEmpty()) {
				notes.add(note);
			}
		}
		return notes;
	}

	/** {@link #notes} joined into one string (empty when there are none). */
	String note(long execTotal, long allocTotal) {
		return String.join(" ", notes(execTotal, allocTotal));
	}

	private String testRunNote(long execTotal) {
		boolean underRunner = execTotal >= MIN_EXEC_SAMPLES && this.runnerSamples >= execTotal * RUNS_UNDER;
		if (this.launcher == null && !underRunner) {
			return "";
		}
		String by = (this.launcher != null) ? this.launcher : name(this.runner);
		return "Recorded from a test run (" + by + ") — fixture setup, test data and assertions are part of "
				+ "these numbers.";
	}

	private String mockNote(long execTotal, long allocTotal) {
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
		return "Looks test-harness dominated — " + String.join(" and ", parts) + " ran inside a mock framework ("
				+ name(this.library) + "), so these numbers describe the harness more than the code under test; "
				+ "a plain driver (`bench --main`) gives representative ones.";
	}

	/** A package prefix without its trailing dot. */
	private static String name(String prefix) {
		return prefix.substring(0, prefix.length() - 1);
	}

	private static String percent(long part, long total) {
		return String.format(Locale.ROOT, "%.0f%%", 100.0 * part / total);
	}

}
