package org.alexmond.jvmlens;

import java.io.IOException;
import java.net.MalformedURLException;
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
 * Attaches to a running JVM and drives JFR through the platform FlightRecorder MXBean — a
 * one-shot timed {@link #capture}, or a continuous ring-buffer {@link #watch} that dumps
 * a rolling window on an interval. Uses only public, modular JDK APIs ({@code jdk.attach}
 * and {@code jdk.management.jfr}) — no internal {@code --add-exports} and no external
 * {@code jcmd} process, keeping the headless single-binary positioning intact.
 */
final class LiveCapture {

	private static final String FLIGHT_RECORDER = "jdk.management.jfr:type=FlightRecorder";

	private LiveCapture() {
	}

	/**
	 * Capture a {@code durationSeconds} JFR recording from the target JVM into a temp
	 * file.
	 * @param pid the target JVM process id
	 * @param durationSeconds how long to record
	 * @param settings predefined JFR configuration name ({@code profile} or
	 * {@code default})
	 * @param warmupSeconds seconds to wait after attach before recording (skip startup)
	 * @return the path of the captured recording (caller owns it)
	 * @throws IOException if attach or capture fails
	 * @throws InterruptedException if interrupted while recording
	 */
	static Path capture(String pid, int durationSeconds, String settings, int warmupSeconds)
			throws IOException, InterruptedException {
		return withRecorder(pid, (fr) -> record(fr, durationSeconds, settings, warmupSeconds));
	}

	/**
	 * Run a continuous ring-buffer recording on the target, dumping a rolling window to a
	 * temp file every {@code intervalSeconds} and handing it to {@code sink}.
	 * @param pid the target JVM process id
	 * @param intervalSeconds seconds between snapshot dumps
	 * @param maxAgeSeconds ring-buffer window kept on the target
	 * @param settings predefined JFR configuration name
	 * @param snapshots number of snapshots to take, or {@code <= 0} to run until
	 * interrupted
	 * @param sink receives each dumped snapshot (caller deletes it)
	 * @throws IOException if attach or capture fails
	 * @throws InterruptedException if interrupted while recording
	 */
	static void watch(String pid, int intervalSeconds, int maxAgeSeconds, String settings, int snapshots,
			SnapshotSink sink) throws IOException, InterruptedException {
		withRecorder(pid, (fr) -> {
			watchLoop(fr, intervalSeconds, maxAgeSeconds, settings, snapshots, sink);
			return null;
		});
	}

	private static <T> T withRecorder(String pid, RecorderAction<T> action) throws IOException, InterruptedException {
		VirtualMachine vm;
		try {
			vm = VirtualMachine.attach(pid);
		}
		catch (AttachNotSupportedException ex) {
			throw new IOException("cannot attach to JVM " + pid + " (" + ex.getMessage() + ")", ex);
		}
		try {
			String address = vm.startLocalManagementAgent();
			try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
				MBeanServerConnection conn = connector.getMBeanServerConnection();
				FlightRecorderMXBean fr = JMX.newMXBeanProxy(conn, new ObjectName(FLIGHT_RECORDER),
						FlightRecorderMXBean.class);
				return action.run(fr);
			}
			catch (MalformedURLException | MalformedObjectNameException ex) {
				throw new IOException("JFR management connection failed (" + ex.getMessage() + ")", ex);
			}
		}
		finally {
			vm.detach();
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

	/** An action against the target's FlightRecorder MXBean while attached. */
	@FunctionalInterface
	private interface RecorderAction<T> {

		T run(FlightRecorderMXBean recorder) throws IOException, InterruptedException;

	}

}
