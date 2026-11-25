package org.alexmond.jvmlens.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
	void launchTriggerArgsParseIntoTriggerCommands() {
		// on-* launch args map to the equivalent in-flight `trigger` commands (like
		// scope).
		assertThat(JvmlensAgent.triggerCommands("250", "80", "40")).containsExactly("trigger gc-ms 250",
				"trigger cpu-pct 80", "trigger old-objects 40");
		// blank/absent dimensions contribute nothing
		assertThat(JvmlensAgent.triggerCommands("250", null, "  ")).containsExactly("trigger gc-ms 250");
		assertThat(JvmlensAgent.triggerCommands(null, null, null)).isEmpty();
	}

	@Test
	void launchTriggerCommandsArmBreachOnlyWhenReplayedIntoControl() {
		// End-to-end: replaying the launch commands arms a real control's WatchTrigger,
		// so
		// a headless monitor emits only on a breach with no control channel.
		AgentControl control = new AgentControl(true, "profile", 60, Set.of(), List.of("org.alexmond.jvmlens"), (d) -> {
		});
		JvmlensAgent.triggerCommands("250", "80", null).forEach(control::apply);
		assertThat(control.trigger().active()).isTrue();
		assertThat(control.trigger().gcMillis()).isEqualTo(250);
		assertThat(control.trigger().cpuShare()).isEqualTo(0.80);
		assertThat(control.trigger().oldObjects()).isZero();
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

	@Test
	void summaryFileIsNeverObservedEmptyWhileRewritten() throws Exception {
		// #195: the summary used to be rewritten in place (truncate, then write), so a
		// reader -- or a JVM exit -- landing between the two saw an empty file. Each
		// rewrite must replace the file whole: a concurrent reader only ever sees a
		// complete summary.
		Recording recording = new Recording(Configuration.getConfiguration("profile"));
		recording.start();
		Path dir = Files.createTempDirectory("jvmlens-agent-test");
		Path out = dir.resolve("agent.md");
		AtomicBoolean stop = new AtomicBoolean();
		AtomicInteger reads = new AtomicInteger();
		AtomicReference<String> torn = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			while (!stop.get() && torn.get() == null) {
				try {
					String seen = Files.readString(out);
					reads.incrementAndGet();
					if (!seen.startsWith("# JVM profile summary")) {
						torn.set(seen);
					}
				}
				catch (IOException ex) {
					// the file exists from the first snapshot on: a rename never hides it
					torn.set("unreadable: " + ex);
				}
			}
		}, "summary-reader");
		try {
			JvmlensAgent.snapshot(recording, out, Scope.defaults());
			reader.start();
			for (int i = 0; i < 15 && torn.get() == null; i++) {
				JvmlensAgent.snapshot(recording, out, Scope.defaults());
			}
			stop.set(true);
			reader.join(5_000);
			assertThat(reads.get()).as("the reader actually raced the writer").isPositive();
			assertThat(torn.get()).as("a concurrent reader saw a torn summary").isNull();
			assertThat(Files.readString(out)).startsWith("# JVM profile summary");
		}
		finally {
			stop.set(true);
			recording.close();
			try (var files = Files.list(dir)) {
				for (Path f : files.toList()) {
					Files.deleteIfExists(f);
				}
			}
			Files.deleteIfExists(dir);
		}
	}

}
