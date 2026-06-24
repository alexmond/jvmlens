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
import one.profiler.AsyncProfilerLoader;

/**
 * Drives JFR on a local target JVM through the platform FlightRecorder MXBean — a
 * one-shot timed {@link #capture}, a continuous ring-buffer {@link #watch}, or a
 * higher-fidelity {@link #captureAsync} via async-profiler. The target is reached by
 * <em>local attach</em> (by pid, via {@code jdk.attach}); to reach a remote server, run
 * jvmlens on that host. Uses only public, modular JDK APIs — no internal
 * {@code --add-exports} and no {@code jcmd}.
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

	/**
	 * Capture from a local JVM with async-profiler (higher fidelity: native frames),
	 * written as JFR so the same {@link Summarizer} consumes it. Local pid only —
	 * async-profiler loads a native agent into the target and cannot work over JMX.
	 * @param pid the target JVM process id
	 * @param durationSeconds how long to record
	 * @param warmupSeconds seconds to wait before recording starts
	 * @param event async-profiler event (e.g. {@code cpu}, {@code itimer}, {@code alloc})
	 * @return the captured recording (caller owns it)
	 * @throws IOException if the profiler fails or produces no data
	 * @throws InterruptedException if interrupted during warmup
	 */
	static Path captureAsync(String pid, int durationSeconds, int warmupSeconds, String event)
			throws IOException, InterruptedException {
		if (warmupSeconds > 0) {
			Thread.sleep(warmupSeconds * 1000L);
		}
		Path out = Files.createTempFile("jvmlens-async", ".jfr");
		AsyncProfilerLoader.executeProfiler("-e", event, "-d", Integer.toString(durationSeconds), "-o", "jfr", "-f",
				out.toString(), pid);
		if (Files.size(out) == 0) {
			Files.deleteIfExists(out);
			throw new IOException("async-profiler produced no data (event=" + event + ", pid " + pid + ")");
		}
		return out;
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

	/** The capture engine: JDK Flight Recorder (default) or async-profiler. */
	enum Engine {

		/** JDK Flight Recorder — the prod-safe default. */
		JFR,
		/** async-profiler — higher fidelity (native frames), local pid only. */
		ASYNC

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
