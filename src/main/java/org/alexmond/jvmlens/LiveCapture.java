package org.alexmond.jvmlens;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * Attaches to a running JVM and captures a timed JFR recording through the platform
 * FlightRecorder MXBean. Uses only public, modular JDK APIs ({@code jdk.attach} and
 * {@code jdk.management.jfr}) — no internal {@code --add-exports} and no external
 * {@code jcmd} process, keeping the single-binary, headless positioning intact.
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
				return record(fr, durationSeconds, settings, warmupSeconds);
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

}
