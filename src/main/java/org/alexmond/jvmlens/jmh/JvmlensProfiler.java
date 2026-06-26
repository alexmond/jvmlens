package org.alexmond.jvmlens.jmh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.ExternalProfiler;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.Result;

import org.alexmond.jvmlens.Scope;
import org.alexmond.jvmlens.Summarizer;

/**
 * A JMH {@link ExternalProfiler} that records the benchmark fork with JFR and prints the
 * jvmlens summary inline when the trial ends — so {@code java -jar benchmarks.jar -prof
 * org.alexmond.jvmlens.jmh.JvmlensProfiler} gives you the ranked hot paths / allocation
 * sites next to JMH's number, no separate {@code analyze} step.
 *
 * <p>
 * Options (after a colon, {@code key=value} separated by {@code ;}): {@code appPackage}
 * (application-frame scope, {@code +}-separate several) and {@code report}
 * (full/cpu/memory/locks/gc/...). Ships in the dependency-light {@code jvmlens-*-jmh.jar}
 * (engine + this profiler, no Spring/picocli/jmh) — put it and {@code jmh-core} on the
 * benchmark's classpath.
 */
public class JvmlensProfiler implements ExternalProfiler {

	private final Path jfr;

	private List<String> appPackages = List.of();

	private Summarizer.Report report = Summarizer.Report.FULL;

	/** No-arg form: {@code -prof org.alexmond.jvmlens.jmh.JvmlensProfiler}. */
	public JvmlensProfiler() throws IOException {
		this.jfr = Files.createTempFile("jvmlens-jmh", ".jfr");
	}

	/**
	 * Options form: {@code -prof "...JvmlensProfiler:appPackage=com.acme;report=cpu"}.
	 */
	public JvmlensProfiler(String initLine) throws IOException {
		this();
		parse(initLine);
	}

	private void parse(String initLine) {
		if (initLine == null) {
			return;
		}
		for (String pair : initLine.split("[;,]")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = pair.substring(0, eq).trim();
			String value = pair.substring(eq + 1).trim();
			if ("appPackage".equals(key)) {
				this.appPackages = List.of(value.split("\\+"));
			}
			else if ("report".equals(key)) {
				applyReport(value);
			}
		}
	}

	private void applyReport(String value) {
		try {
			this.report = Summarizer.Report.valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			System.out.println("jvmlens: unknown report focus `" + value + "` — using full");
		}
	}

	@Override
	public Collection<String> addJVMInvokeOptions(BenchmarkParams params) {
		return Collections.emptyList();
	}

	@Override
	public Collection<String> addJVMOptions(BenchmarkParams params) {
		return List.of("-XX:StartFlightRecording=filename=" + this.jfr + ",settings=profile,dumponexit=true");
	}

	@Override
	public void beforeTrial(BenchmarkParams params) {
		// nothing to do before the fork starts — JFR is armed via addJVMOptions
	}

	@Override
	public Collection<? extends Result> afterTrial(BenchmarkResult result, long pid, File stdOut, File stdErr) {
		try {
			Scope scope = Scope.of(this.appPackages, List.of());
			System.out.println("\n" + Summarizer.summarize(this.jfr, Summarizer.Format.MARKDOWN, scope, this.report));
		}
		catch (IOException ex) {
			System.out.println("jvmlens: could not summarize the benchmark recording: " + ex.getMessage());
		}
		finally {
			try {
				Files.deleteIfExists(this.jfr);
			}
			catch (IOException ignored) {
				// best-effort cleanup of the temp recording
			}
		}
		return Collections.<Result>emptyList();
	}

	@Override
	public boolean allowPrintOut() {
		return true;
	}

	@Override
	public boolean allowPrintErr() {
		return true;
	}

	@Override
	public String getDescription() {
		return "jvmlens — LLM-ready JFR summary of the benchmark, printed when the trial ends";
	}

}
