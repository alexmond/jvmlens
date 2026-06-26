package org.alexmond.jvmlens.jmh;

import java.util.Collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

}
