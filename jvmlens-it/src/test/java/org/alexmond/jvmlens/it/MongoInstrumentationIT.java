package org.alexmond.jvmlens.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end proof that the {@code mongo} dimension fires against a real driver: launch
 * {@link MongoWorkload} (mongodb-driver-sync, against a Testcontainers MongoDB) as a
 * subprocess with the built agent attached as {@code -javaagent:…=mongo}, and assert the
 * emitted summary carries the MongoDB section, the per-collection label
 * ({@code users.find} — #147), its app call-site anchor, and the N+1 document-fetch flag.
 * The agent matches {@code com.mongodb.client.MongoCollection} by name (no compile dep),
 * so this is the only test that catches a driver-internal change silently stopping the
 * advice from installing (#146). Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoInstrumentationIT {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

	@Test
	void mongoOpsAreCapturedPerCollectionWithTheNplusOneFlag(@TempDir Path tmp) throws Exception {
		String agentJar = System.getProperty("jvmlens.agent.jar");
		assumeTrue(agentJar != null && Files.exists(Path.of(agentJar)),
				"built agent jar not found (set jvmlens.agent.jar); run the full reactor build");

		Path summary = tmp.resolve("agent.md");
		Path hostLog = tmp.resolve("host.log");
		String java = System.getProperty("java.home") + "/bin/java";
		String classpath = System.getProperty("java.class.path");

		Process host = new ProcessBuilder(java,
				"-javaagent:" + agentJar + "=out=" + summary + ",mongo,scope=app:org.alexmond.jvmlens.it,interval=2",
				"-cp", classpath, "org.alexmond.jvmlens.it.MongoWorkload", MONGO.getConnectionString())
			.redirectErrorStream(true)
			.redirectOutput(hostLog.toFile())
			.start();

		boolean exited = host.waitFor(120, TimeUnit.SECONDS);
		String log = Files.exists(hostLog) ? Files.readString(hostLog, StandardCharsets.UTF_8) : "";
		if (!exited) {
			host.destroyForcibly();
		}

		assertThat(exited).as("workload did not exit within 120s\n%s", log).isTrue();
		assertThat(log).as("workload ran under the agent\n%s", log).contains("JVMLENS-IT-READY");
		assertThat(host.exitValue()).as("clean exit\n%s", log).isZero();

		String md = Files.readString(summary, StandardCharsets.UTF_8);
		assertThat(md).as("mongo section, per-collection label, anchor and N+1 flag all rendered\n%s", md)
			.contains("MongoDB operations (by total time)")
			.contains("users.find")
			.contains("· at org.alexmond.jvmlens.it.MongoWorkload")
			.contains("possible N+1 document fetch");
	}

}
