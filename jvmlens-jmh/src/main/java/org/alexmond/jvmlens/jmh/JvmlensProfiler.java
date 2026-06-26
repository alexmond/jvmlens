package org.alexmond.jvmlens.jmh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.ExternalProfiler;
import org.openjdk.jmh.profile.ProfilerException;
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
 * (application-frame scope; {@code +} or {@code ,}-separate several — {@code appPackages}
 * is accepted as an alias so the scope syntax matches the CLI's {@code -a}) and
 * {@code report} (full/cpu/memory/locks/gc/...). An <em>unknown</em> option key is a hard
 * error (a {@link ProfilerException} with a did-you-mean suggestion) rather than a silent
 * no-op — a misspelled {@code appPackage} that produced an unscoped summary used to cost
 * a whole capture before you noticed (field-finding #53 item 6). Ships in the
 * dependency-light {@code jvmlens-*-jmh.jar} (engine + this profiler, no
 * Spring/picocli/jmh) — put it and {@code jmh-core} on the benchmark's classpath.
 */
public class JvmlensProfiler implements ExternalProfiler {

	/** Recognized option keys, used for the did-you-mean suggestion on a typo. */
	private static final List<String> KNOWN_KEYS = List.of("appPackage", "report");

	private final Path jfr;

	private List<String> appPackages = List.of();

	private Summarizer.Report report = Summarizer.Report.FULL;

	/** No-arg form: {@code -prof org.alexmond.jvmlens.jmh.JvmlensProfiler}. */
	public JvmlensProfiler() throws IOException {
		this.jfr = Files.createTempFile("jvmlens-jmh", ".jfr");
	}

	/**
	 * Options form: {@code -prof "...JvmlensProfiler:appPackage=com.acme;report=cpu"}.
	 * @param initLine the JMH option string, or {@code null}
	 * @throws IOException if the temp recording file cannot be created
	 * @throws ProfilerException if an option key is unknown or a pair is malformed
	 */
	public JvmlensProfiler(String initLine) throws IOException, ProfilerException {
		this();
		parse(initLine);
	}

	private void parse(String initLine) throws ProfilerException {
		if (initLine == null) {
			return;
		}
		for (String pair : initLine.split(";")) {
			String trimmed = pair.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			int eq = trimmed.indexOf('=');
			if (eq <= 0) {
				throw new ProfilerException("jvmlens: malformed option `" + trimmed + "` — expected key=value");
			}
			applyOption(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
		}
	}

	private void applyOption(String key, String value) throws ProfilerException {
		switch (key) {
			case "appPackage", "appPackages" -> {
				this.appPackages = List.of(value.split("[+,]"));
			}
			case "report" -> applyReport(value);
			default -> throw new ProfilerException(unknownOption(key));
		}
	}

	private static String unknownOption(String key) {
		String nearest = KNOWN_KEYS.stream().min(Comparator.comparingInt((k) -> distance(key, k))).orElse("appPackage");
		return "jvmlens: unknown profiler option `" + key + "` — did you mean `" + nearest + "`? (valid: "
				+ String.join(", ", KNOWN_KEYS) + ")";
	}

	private static int distance(String a, String b) {
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			for (int j = 1; j <= b.length(); j++) {
				int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
			}
		}
		return d[a.length()][b.length()];
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
