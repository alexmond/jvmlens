package org.alexmond.jvmlens.agent;

import java.nio.file.Files;
import java.nio.file.Path;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.Scope;

import static org.assertj.core.api.Assertions.assertThat;

class JvmlensAgentTest {

	@Test
	void agentScopeExcludesOwnPackageButKeepsApp() {
		assertThat(JvmlensAgent.agentScope().isApplication("org.alexmond.jvmlens.Summarizer")).isFalse();
		assertThat(JvmlensAgent.agentScope().isApplication("org.alexmond.unitrack.web.UserService")).isTrue();
	}

	@Test
	void snapshotWritesSummaryFromInProcessRecording() throws Exception {
		Recording recording = new Recording(Configuration.getConfiguration("profile"));
		recording.start();
		long end = System.nanoTime() + 2_000_000_000L;
		double x = 0;
		while (System.nanoTime() < end) {
			for (int i = 0; i < 50_000; i++) {
				x += Math.sqrt(i);
			}
		}
		if (x < 0) {
			throw new IllegalStateException("unreachable");
		}
		Path out = Files.createTempFile("jvmlens-agent-test", ".md");
		try {
			JvmlensAgent.snapshot(recording, out, Scope.defaults());
			String summary = Files.readString(out);
			assertThat(summary).contains("# JVM profile summary");
			assertThat(summary).contains("Suspected cause");
		}
		finally {
			recording.close();
			Files.deleteIfExists(out);
		}
	}

}
