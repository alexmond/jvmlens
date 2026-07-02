package org.alexmond.jvmlens.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
	void launchScopeArgParsesIntoScopeCommands() {
		// #133: a headless monitor can now pin the app scope at launch, no control file.
		assertThat(JvmlensAgent.scopeCommands("app:org.alexmond.unitrack"))
			.containsExactly("scope app org.alexmond.unitrack");
		// '+'-separated multi-prefix, both sides; a bare prefix defaults to app
		assertThat(JvmlensAgent.scopeCommands("app:org.alexmond.unitrack+exclude:org.h2+com.foo"))
			.containsExactly("scope app org.alexmond.unitrack", "scope exclude org.h2", "scope app com.foo");
		assertThat(JvmlensAgent.scopeCommands(null)).isEmpty();
		assertThat(JvmlensAgent.scopeCommands("  ")).isEmpty();
	}

	@Test
	void launchScopeCommandsPinAttributionWhenReplayedIntoControl() {
		// End-to-end: replaying the launch commands into a real control yields a scope
		// pinned to the target module from the first sample (same path as `scope app`).
		AgentControl control = new AgentControl(true, "profile", 60, Set.of(), List.of("org.alexmond.jvmlens"), (d) -> {
		});
		JvmlensAgent.scopeCommands("app:org.alexmond.unitrack+exclude:org.h2").forEach(control::apply);
		Scope scope = control.scope();
		assertThat(scope.isApplication("org.alexmond.unitrack.web.UserService")).isTrue();
		assertThat(scope.isApplication("org.h2.mvstore.Page")).isFalse();
		// the include is now non-empty, so an unrelated third-party frame is not
		// application
		assertThat(scope.isApplication("com.thirdparty.Foo")).isFalse();
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
		Path history = Files.createTempFile("jvmlens-agent-test", ".jsonl");
		try {
			JvmlensAgent.snapshot(recording, out, history, Scope.defaults());
			JvmlensAgent.snapshot(recording, out, history, Scope.defaults());
			String summary = Files.readString(out);
			assertThat(summary).contains("# JVM profile summary");
			assertThat(summary).contains("Suspected cause");
			// history is appended, not overwritten: one JSON line per snapshot
			assertThat(Files.readAllLines(history)).hasSize(2).allSatisfy((l) -> assertThat(l).startsWith("{\"t\":"));
		}
		finally {
			recording.close();
			Files.deleteIfExists(out);
			Files.deleteIfExists(history);
		}
	}

}
