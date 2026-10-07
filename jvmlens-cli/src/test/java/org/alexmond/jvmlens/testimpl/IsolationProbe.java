package org.alexmond.jvmlens.testimpl;

/**
 * A bench workload that reports whether it can see jvmlens's own dependencies — the probe
 * for {@code BenchCommandTest}'s {@code --cp} isolation check (#164). Its only channel
 * back is a system property, since an isolated workload shares nothing but the JDK with
 * the harness.
 */
public final class IsolationProbe {

	/**
	 * System property the probe writes: {@code true} if a jvmlens dependency leaked in.
	 */
	public static final String SEES_HARNESS = "jvmlens.test.probe.seesHarness";

	private IsolationProbe() {
	}

	public static void main(String[] args) {
		boolean visible;
		try {
			Class.forName("picocli.CommandLine", false, IsolationProbe.class.getClassLoader());
			visible = true;
		}
		catch (ClassNotFoundException ex) {
			visible = false;
		}
		System.setProperty(SEES_HARNESS, Boolean.toString(visible));
	}

}
