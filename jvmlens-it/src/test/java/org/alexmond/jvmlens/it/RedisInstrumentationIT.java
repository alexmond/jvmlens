package org.alexmond.jvmlens.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end proof that the {@code redis} dimension fires against a real driver: launch
 * {@link RedisWorkload} (Lettuce, against a Testcontainers Redis on a
 * {@link GenericContainer} — there is no official Redis Testcontainers module) as a
 * subprocess with the built agent attached as {@code -javaagent:…=redis}, and assert the
 * emitted summary carries the Redis section, its app call-site anchor, and the
 * single-key-reads N+1-round-trips flag. The agent matches Lettuce's async command
 * interface by name (the sync API delegates to it), so this catches a driver-internal
 * change silently stopping the advice from installing (#146). Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisInstrumentationIT {

	private static final int REDIS_PORT = 6379;

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

	@Test
	void redisCommandsAreCapturedWithTheNplusOneRoundTripFlag(@TempDir Path tmp) throws Exception {
		String agentJar = System.getProperty("jvmlens.agent.jar");
		assumeTrue(agentJar != null && Files.exists(Path.of(agentJar)),
				"built agent jar not found (set jvmlens.agent.jar); run the full reactor build");

		Path summary = tmp.resolve("agent.md");
		Path hostLog = tmp.resolve("host.log");
		String java = System.getProperty("java.home") + "/bin/java";
		String classpath = System.getProperty("java.class.path");
		String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT);

		Process host = new ProcessBuilder(java,
				"-javaagent:" + agentJar + "=out=" + summary + ",redis,scope=app:org.alexmond.jvmlens.it,interval=2",
				"-cp", classpath, "org.alexmond.jvmlens.it.RedisWorkload", uri)
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
		assertThat(md).as("redis section, anchor and N+1-round-trips flag all rendered\n%s", md)
			.contains("Redis commands (by total time)")
			.contains("· at org.alexmond.jvmlens.it.RedisWorkload")
			.contains("many single-key reads");
	}

}
