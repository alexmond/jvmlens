package org.alexmond.jvmlens.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end agent regression: launch the real {@link SampleApp} (Spring Boot + JPA +
 * web, against a Testcontainers Postgres) as a subprocess with the built agent jar
 * attached as {@code -javaagent:…=db,web} — exactly how production runs it. The context
 * must boot cleanly (printing {@code JVMLENS-IT-READY} and exiting 0). This reproduces
 * and gates both #68 failure modes that the in-JVM unit tests miss: the un-relocated
 * multi-release ByteBuddy crash (Bug 1) and the Hibernate {@code EntityManagerFactory}
 * StackOverflow from instrumenting synthetic/generated classes (Bug 2).
 *
 * <p>
 * The host is deliberately widened (#79) to stress the matchers/sanitizers on a real
 * boot: a {@code @Transactional} Spring-proxied service (CGLIB/AOP-proxy surface) and a
 * raw {@code JdbcTemplate} path that fires SQL carrying the {@code SqlSanitizer}-SOE
 * shapes (a long quoted literal + a long {@code IN} list). The agent must survive all of
 * it — boot clean and still capture SQL — proving the fix and the fail-open guard (#74)
 * hold together.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentBootIT {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	@Test
	void hostBootsCleanlyUnderTheAgentWithDbAndWeb(@TempDir Path tmp) throws Exception {
		String agentJar = System.getProperty("jvmlens.agent.jar");
		assumeTrue(agentJar != null && Files.exists(Path.of(agentJar)),
				"built agent jar not found (set jvmlens.agent.jar); run the full reactor build");

		Path summary = tmp.resolve("agent.md");
		Path hostLog = tmp.resolve("host.log");
		String java = System.getProperty("java.home") + "/bin/java";
		String classpath = System.getProperty("java.class.path");

		Process host = new ProcessBuilder(java, "-javaagent:" + agentJar + "=out=" + summary + ",db,web,interval=2",
				"-cp", classpath, "org.alexmond.jvmlens.it.SampleApp",
				"--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"--spring.datasource.username=" + POSTGRES.getUsername(),
				"--spring.datasource.password=" + POSTGRES.getPassword())
			.redirectErrorStream(true)
			.redirectOutput(hostLog.toFile())
			.start();

		boolean exited = host.waitFor(150, TimeUnit.SECONDS);
		String log = Files.exists(hostLog) ? Files.readString(hostLog, StandardCharsets.UTF_8) : "";
		if (!exited) {
			host.destroyForcibly();
		}

		assertThat(exited).as("host JVM did not exit within 150s — likely hung at boot\n%s", log).isTrue();
		assertThat(log)
			.as("host must boot cleanly under -javaagent db,web (no EMF StackOverflow / "
					+ "NoClassDefFound 'wrong name')\n%s", log)
			.contains("JVMLENS-IT-READY");
		assertThat(host.exitValue()).as("clean exit\n%s", log).isZero();
		// the db dimension actually captured SQL from the Hibernate/Flyway traffic
		assertThat(Files.readString(summary, StandardCharsets.UTF_8)).containsIgnoringCase("SQL");
	}

}
