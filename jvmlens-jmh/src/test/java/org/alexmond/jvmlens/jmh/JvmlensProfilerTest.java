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

	@Test
	void measuredAbVerdictCallsAPhantomReductionNotSignificant() {
		// #104, the burned case: sampled total fell ~10% but measured bytes/op is
		// unchanged
		// (782,881 ± 17,200 → 780,083 ± 19,854 across 4 forks) → NOT significant + a
		// disagree note.
		String v = JvmlensProfiler.allocVerdict(782_881, 17_200, 780_083, 19_854, 4, -10.0);
		assertThat(v).contains("NOT significant")
			.contains("sampled")
			.contains("disagrees")
			.doesNotContain("single fork");
	}

	@Test
	void measuredAbVerdictCallsARealDropSignificant() {
		String v = JvmlensProfiler.allocVerdict(1_000_000, 20_000, 700_000, 20_000, 4, -30.0);
		assertThat(v).contains("SIGNIFICANT").doesNotContain("NOT significant").doesNotContain("disagrees");
	}

	@Test
	void measuredAbVerdictWarnsOnASingleFork() {
		String v = JvmlensProfiler.allocVerdict(1_000_000, 0, 980_000, 0, 1, -2.0);
		assertThat(v).contains("single fork understates");
	}

}
