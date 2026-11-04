package org.alexmond.jvmlens;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class VersionTest {

	/** The project version, handed in by surefire from the pom — not from the reader. */
	private static final String EXPECTED = System.getProperty("jvmlens.expectedVersion");

	@Test
	void versionReportsTheBuildVersion() {
		// #192: `--version` printed `jvmlens 0.1.0` on a 0.3.2-SNAPSHOT build
		assertThat(EXPECTED).isNotBlank();
		StringWriter out = new StringWriter();
		new CommandLine(new JvmlensCommand()).printVersionHelp(new PrintWriter(out, true));
		assertThat(out.toString().strip()).isEqualTo("jvmlens " + EXPECTED);
		// the MCP server's serverInfo reads the same source
		assertThat(JvmlensVersion.get()).isEqualTo(EXPECTED);
	}

	@Test
	void versionWithoutBuildInfoSaysUnknownRatherThanAStaleNumber() {
		assertThat(JvmlensVersion.read(null)).isEqualTo("unknown");
		assertThat(JvmlensVersion.read(new ByteArrayInputStream("build.name=x\n".getBytes(StandardCharsets.UTF_8))))
			.isEqualTo("unknown");
		assertThat(JvmlensVersion
			.read(new ByteArrayInputStream("build.version= 1.2.3 \n".getBytes(StandardCharsets.UTF_8))))
			.isEqualTo("1.2.3");
	}

}
