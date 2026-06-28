package org.alexmond.jvmlens.jmh;

import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.ProfilerException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JvmlensProfilerTest {

	@Test
	void armsAJfrRecordingViaJvmOptions() throws Exception {
		JvmlensProfiler p = new JvmlensProfiler();
		Collection<String> opts = p.addJVMOptions(null);
		assertThat(opts).anyMatch((o) -> o.startsWith("-XX:StartFlightRecording=") && o.contains("settings=profile")
				&& o.contains("dumponexit=true"));
		assertThat(p.addJVMInvokeOptions(null)).isEmpty();
		assertThat(p.allowPrintOut()).isTrue();
		assertThat(p.getDescription()).contains("jvmlens");
	}

	@Test
	void acceptsAnOptionLineWithoutThrowing() throws Exception {
		// -prof "JvmlensProfiler:appPackage=com.acme+org.example;report=cpu"
		JvmlensProfiler p = new JvmlensProfiler("appPackage=com.acme+org.example;report=cpu");
		assertThat(p.addJVMOptions(null)).isNotEmpty();
		// an unknown report focus degrades to full rather than failing
		assertThat(new JvmlensProfiler("report=nonsense").addJVMOptions(null)).isNotEmpty();
	}

	@Test
	void acceptsTheAppPackagesAliasAndCommaSeparator() throws Exception {
		// CLI-style scope syntax (plural key, comma-separated) is accepted, matching `-a`
		assertThat(new JvmlensProfiler("appPackages=com.acme,org.example").addJVMOptions(null)).isNotEmpty();
	}

	@Test
	void hardErrorsOnAnUnknownOptionKeyWithASuggestion() {
		// the classic silent-misconfig: plural-looking typo, used to no-op and unscope
		assertThatThrownBy(() -> new JvmlensProfiler("appPackgae=com.acme")).isInstanceOf(ProfilerException.class)
			.hasMessageContaining("unknown profiler option")
			.hasMessageContaining("did you mean `appPackage`");
	}

	@Test
	void hardErrorsOnAMalformedPair() {
		assertThatThrownBy(() -> new JvmlensProfiler("appPackage")).isInstanceOf(ProfilerException.class)
			.hasMessageContaining("expected key=value");
	}

	@Test
	void acceptsKeepAndBaselineOptions() throws Exception {
		// #50 item 2: keep the fork's recording + diff against a prior one, inside JMH
		assertThat(new JvmlensProfiler("keep=/tmp/run.jfr;baseline=/tmp/prev.jfr").addJVMOptions(null)).isNotEmpty();
	}

	@Test
	void stillSuggestsForATypoNearTheNewKeys() {
		assertThatThrownBy(() -> new JvmlensProfiler("baselin=/tmp/prev.jfr")).isInstanceOf(ProfilerException.class)
			.hasMessageContaining("did you mean `baseline`");
	}

	@Test
	void dropsHarnessSocketIoByDefault() throws Exception {
		// #100: a JMH fork's only socket traffic is the harness control socket — drop it.
		String opts = String.join(" ", new JvmlensProfiler().addJVMOptions(null));
		assertThat(opts).contains("jdk.SocketRead#enabled=false").contains("jdk.SocketWrite#enabled=false");
		// socketio=true keeps them
		String kept = String.join(" ", new JvmlensProfiler("socketio=true").addJVMOptions(null));
		assertThat(kept).doesNotContain("jdk.SocketRead#enabled=false");
	}

	@Test
	void socketioTypoStillSuggests() {
		assertThatThrownBy(() -> new JvmlensProfiler("socketIO=true")).isInstanceOf(ProfilerException.class)
			.hasMessageContaining("did you mean `socketio`");
	}

}
