package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import jdk.management.jfr.FlightRecorderMXBean;

/**
 * Drives JFR on a target JVM through the platform FlightRecorder MXBean — a one-shot
 * timed {@link #capture}, or a continuous ring-buffer {@link #watch} that dumps a rolling
 * window on an interval. The target is reached either by <em>local attach</em> (by pid,
 * via {@code jdk.attach}) or by connecting to a <em>remote JMX</em> service URL. Uses
 * only public, modular JDK APIs — no internal {@code --add-exports} and no external
 * {@code jcmd}.
 */
final class LiveCapture {

	private static final String FLIGHT_RECORDER = "jdk.management.jfr:type=FlightRecorder";

	private LiveCapture() {
	}

	/**
	 * Capture a timed recording from a local JVM (attach by pid) into a temp file.
	 * @param pid the target JVM process id
	 * @param durationSeconds how long to record
	 * @param settings predefined JFR configuration name ({@code profile} or
	 * {@code default})
	 * @param warmupSeconds seconds to wait after attach before recording (skip startup)
	 * @return the captured recording (caller owns it)
	 * @throws IOException if attach or capture fails
	 * @throws InterruptedException if interrupted while recording
	 */
	static Path capture(String pid, int durationSeconds, String settings, int warmupSeconds)
			throws IOException, InterruptedException {
		return withLocal(pid, (fr) -> record(fr, durationSeconds, settings, warmupSeconds));
	}

	/** Capture a timed recording from a remote JVM reached over a JMX service URL. */
	static Path captureRemote(String jmxUrl, int durationSeconds, String settings, int warmupSeconds)
			throws IOException, InterruptedException {
		return withRemote(jmxUrl, (fr) -> record(fr, durationSeconds, settings, warmupSeconds));
	}

	/**
	 * Continuously record a local JVM, dumping a rolling window to {@code sink} each
	 * interval.
	 * @param pid the target JVM process id
	 * @param intervalSeconds seconds between snapshot dumps
	 * @param maxAgeSeconds ring-buffer window kept on the target
	 * @param settings predefined JFR configuration name
	 * @param snapshots number of snapshots, or {@code <= 0} to run until interrupted
	 * @param sink receives each dumped snapshot (caller deletes it)
	 * @throws IOException if attach or capture fails
	 * @throws InterruptedException if interrupted while recording
	 */
	static void watch(String pid, int intervalSeconds, int maxAgeSeconds, String settings, int snapshots,
			SnapshotSink sink) throws IOException, InterruptedException {
		withLocal(pid, (fr) -> {
			watchLoop(fr, intervalSeconds, maxAgeSeconds, settings, snapshots, sink);
			return null;
		});
	}

	/**
	 * Continuously record a remote JVM (over JMX), dumping a rolling window each
	 * interval.
	 */
	static void watchRemote(String jmxUrl, int intervalSeconds, int maxAgeSeconds, String settings, int snapshots,
			SnapshotSink sink) throws IOException, InterruptedException {
		withRemote(jmxUrl, (fr) -> {
			watchLoop(fr, intervalSeconds, maxAgeSeconds, settings, snapshots, sink);
			return null;
		});
	}

	private static <T> T withLocal(String pid, RecorderAction<T> action) throws IOException, InterruptedException {
		VirtualMachine vm;
		try {
			vm = VirtualMachine.attach(pid);
		}
		catch (AttachNotSupportedException ex) {
			throw new IOException("cannot attach to JVM " + pid + " (" + ex.getMessage() + ")", ex);
		}
		try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(vm.startLocalManagementAgent()))) {
			return runWith(connector.getMBeanServerConnection(), action);
		}
		finally {
			vm.detach();
		}
	}

	private static <T> T withRemote(String jmxUrl, RecorderAction<T> action) throws IOException, InterruptedException {
		try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(serviceUrl(jmxUrl)))) {
			return runWith(connector.getMBeanServerConnection(), action);
		}
	}

	/**
	 * Accept a full {@code service:jmx:...} URL, or expand a {@code host:port} shorthand.
	 */
	private static String serviceUrl(String target) {
		return target.startsWith("service:jmx:") ? target : "service:jmx:rmi:///jndi/rmi://" + target + "/jmxrmi";
	}

	private static <T> T runWith(MBeanServerConnection conn, RecorderAction<T> action)
			throws IOException, InterruptedException {
		try {
			FlightRecorderMXBean fr = JMX.newMXBeanProxy(conn, new ObjectName(FLIGHT_RECORDER),
					FlightRecorderMXBean.class);
			return action.run(fr);
		}
		catch (MalformedObjectNameException ex) {
			throw new IOException("JFR management bean unavailable (" + ex.getMessage() + ")", ex);
		}
	}

	private static Path record(FlightRecorderMXBean fr, int durationSeconds, String settings, int warmupSeconds)
			throws IOException, InterruptedException {
		if (warmupSeconds > 0) {
			Thread.sleep(warmupSeconds * 1000L);
		}
		long id = fr.newRecording();
		try {
			fr.setPredefinedConfiguration(id, settings);
			fr.startRecording(id);
			Thread.sleep(durationSeconds * 1000L);
			fr.stopRecording(id);
			Path out = Files.createTempFile("jvmlens-live", ".jfr");
			fr.copyTo(id, out.toString());
			return out;
		}
		finally {
			fr.closeRecording(id);
		}
	}

	private static void watchLoop(FlightRecorderMXBean fr, int intervalSeconds, int maxAgeSeconds, String settings,
			int snapshots, SnapshotSink sink) throws IOException, InterruptedException {
		long id = fr.newRecording();
		try {
			fr.setRecordingOptions(id, Map.of("maxAge", maxAgeSeconds + "s", "disk", "true"));
			fr.setPredefinedConfiguration(id, settings);
			fr.startRecording(id);
			for (int taken = 0; snapshots <= 0 || taken < snapshots; taken++) {
				Thread.sleep(intervalSeconds * 1000L);
				Path out = Files.createTempFile("jvmlens-watch", ".jfr");
				fr.copyTo(id, out.toString());
				sink.accept(out, taken + 1);
			}
		}
		finally {
			fr.stopRecording(id);
			fr.closeRecording(id);
		}
	}

	/** Receives each dumped snapshot file (and its 1-based index) from {@link #watch}. */
	@FunctionalInterface
	interface SnapshotSink {

		void accept(Path snapshot, int index) throws IOException;

	}

	/** An action against the target's FlightRecorder MXBean while connected. */
	@FunctionalInterface
	private interface RecorderAction<T> {

		T run(FlightRecorderMXBean recorder) throws IOException, InterruptedException;

	}

}
