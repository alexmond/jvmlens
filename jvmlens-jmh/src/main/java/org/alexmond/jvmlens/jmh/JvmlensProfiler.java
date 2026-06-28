package org.alexmond.jvmlens.jmh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

import org.alexmond.jvmlens.ProfileDiff;
import org.alexmond.jvmlens.ProfileSummary;
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
 * is accepted as an alias so the scope syntax matches the CLI's {@code -a}),
 * {@code report} (full/cpu/memory/locks/gc/...), {@code keep} (write the fork's recording
 * to this path instead of deleting it — so it can be the next run's baseline), and
 * {@code baseline} (a prior recording to diff this run against — prints the change report
 * instead of the plain summary, so {@code run → "what changed vs last run"} happens in
 * one JMH command; field-finding #50 item 2), and {@code socketio} (default {@code false}
 * — drops socket I/O events, which on a JMH fork are just the harness control socket; set
 * {@code socketio=true} to keep them; field-finding #100). An <em>unknown</em> option key
 * is a hard error (a {@link ProfilerException} with a did-you-mean suggestion) rather
 * than a silent no-op — a misspelled {@code appPackage} that produced an unscoped summary
 * used to cost a whole capture before you noticed (field-finding #53 item 6).
 *
 * <p>
 * When the run also enables JMH's GC profiler ({@code -prof gc}), the summary is headed
 * with JMH's <em>exact</em> {@code gc.alloc.rate.norm} (bytes/op) so the measured rate
 * sits next to jvmlens's <em>sampled</em> per-site bytes (field-finding #100). Ships in
 * the dependency-light {@code jvmlens-jmh.jar} (engine + this profiler, no
 * Spring/picocli/jmh) — put it and {@code jmh-core} on the benchmark's classpath.
 */
public class JvmlensProfiler implements ExternalProfiler {

	/** Recognized option keys, used for the did-you-mean suggestion on a typo. */
	private static final List<String> KNOWN_KEYS = List.of("appPackage", "report", "keep", "baseline", "socketio");

	private final Path jfr;

	private List<String> appPackages = List.of();

	private Summarizer.Report report = Summarizer.Report.FULL;

	private Path keep;

	private Path baseline;

	/**
	 * Keep socket I/O events? Default false — drops the JMH harness control socket
	 * (#100).
	 */
	private boolean socketIo;

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
			case "keep" -> {
				this.keep = Path.of(value);
			}
			case "baseline" -> {
				this.baseline = Path.of(value);
			}
			case "socketio" -> {
				this.socketIo = "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
			}
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
		String rec = "-XX:StartFlightRecording=filename=" + this.jfr + ",settings=profile,dumponexit=true";
		if (!this.socketIo) {
			// On a JMH fork the only socket traffic is the harness control socket (a
			// rotating
			// localhost:<ephemeral> port) — noise that skews External I/O + the
			// cross-dimension
			// correlation (field-finding #100). Drop it at the source; `socketio=true`
			// keeps it.
			rec += ",jdk.SocketRead#enabled=false,jdk.SocketWrite#enabled=false";
		}
		return List.of(rec);
	}

	@Override
	public void beforeTrial(BenchmarkParams params) {
		// nothing to do before the fork starts — JFR is armed via addJVMOptions
	}

	@Override
	public Collection<? extends Result> afterTrial(BenchmarkResult result, long pid, File stdOut, File stdErr) {
		Result<?> norm = result.getSecondaryResults().get("gc.alloc.rate.norm");
		try {
			Scope scope = Scope.of(this.appPackages, List.of());
			if (this.baseline != null) {
				ProfileSummary before = Summarizer.analyze(this.baseline, scope);
				ProfileSummary after = Summarizer.analyze(this.jfr, scope);
				double sampledPct = (before.allocBytes() > 0)
						? 100.0 * (after.allocBytes() - before.allocBytes()) / before.allocBytes() : 0;
				System.out.println("\n" + measuredAbHead(norm, result, sampledPct) + ProfileDiff.diff(before, after));
			}
			else {
				System.out.println("\n" + jmhAllocNote(result)
						+ Summarizer.summarize(this.jfr, Summarizer.Format.MARKDOWN, scope, this.report));
			}
		}
		catch (IOException ex) {
			System.out.println("jvmlens: could not summarize the benchmark recording: " + ex.getMessage());
		}
		finally {
			retainRecording(norm, result.getParams().getForks());
		}
		return Collections.<Result>emptyList();
	}

	/**
	 * The header for a {@code baseline=} run: a measured allocation A/B verdict (#104)
	 * when both this run and the kept baseline have JMH's exact
	 * {@code gc.alloc.rate.norm}, reconciled against the sampled diff total. Falls back
	 * to the {@link #jmhAllocNote} hint otherwise.
	 */
	private String measuredAbHead(Result<?> norm, BenchmarkResult result, double sampledPct) {
		if (norm == null) {
			return jmhAllocNote(result);
		}
		double[] base = readMeasured(this.baseline);
		if (base == null) {
			return "> Measured A/B unavailable — re-run the baseline with `-prof gc` (and `keep=`) so the "
					+ "next diff can gate on exact bytes/op (#104). Sampled diff below.\n\n";
		}
		int forks = Math.min(result.getParams().getForks(), (int) base[2]);
		return allocVerdict(base[0], base[1], norm.getScore(), errOrZero(norm), forks, sampledPct) + "\n";
	}

	/**
	 * A measured allocation A/B verdict with a significance call: the change is
	 * SIGNIFICANT only if the relative Δ exceeds the combined noise band <em>and</em> the
	 * confidence intervals don't overlap — otherwise the sampled diff may be a phantom
	 * (JIT elision / sampling redistribution). Reconciles the sampled total Δ against the
	 * measured Δ (#104). Pure.
	 */
	static String allocVerdict(double bMean, double bErr, double aMean, double aErr, int forks, double sampledPct) {
		double measuredPct = (bMean != 0) ? 100.0 * (aMean - bMean) / bMean : 0;
		double band = (bMean != 0 && aMean != 0) ? 100.0 * (bErr / bMean + aErr / aMean) : 0;
		boolean ciOverlap = !(aMean + aErr < bMean - bErr || aMean - aErr > bMean + bErr);
		boolean significant = Math.abs(measuredPct) > band && !ciOverlap;
		StringBuilder b = new StringBuilder("> **Measured allocation A/B [exact]**: ");
		b.append(humanBytesPerOp(bMean)).append("/op → ").append(humanBytesPerOp(aMean)).append("/op ");
		b.append(String.format(Locale.ROOT, "(Δ %+.1f%%, n=%d fork(s) each, noise band ±%.1f%%) → **%s**.", measuredPct,
				forks, band, significant ? "SIGNIFICANT" : "NOT significant"));
		if (forks < 2) {
			b.append(" ⚠ a single fork understates cross-fork variance — re-run with `-f 2`+ for a real band.");
		}
		boolean disagree = (!significant && Math.abs(sampledPct) >= 5.0)
				|| (sampledPct * measuredPct < 0 && Math.abs(sampledPct) >= 5.0 && Math.abs(measuredPct) >= 5.0);
		if (disagree) {
			b.append(String.format(Locale.ROOT,
					" The **sampled** total Δ (%+.1f%%) disagrees — likely sampling "
							+ "redistribution / JIT elision, not a real change; trust the measured number.",
					sampledPct));
		}
		return b.append('\n').toString();
	}

	private static double errOrZero(Result<?> norm) {
		double err = norm.getScoreError();
		return Double.isNaN(err) ? 0.0 : err;
	}

	/**
	 * Read a kept recording's sidecar {@code <jfr>.bop} ({@code mean err forks}), or
	 * null.
	 */
	private static double[] readMeasured(Path jfr) {
		Path bop = Path.of(jfr + ".bop");
		try {
			String[] parts = Files.readString(bop).trim().split("\\s+");
			double err = Double.parseDouble(parts[1]);
			return new double[] { Double.parseDouble(parts[0]), Double.isNaN(err) ? 0.0 : err,
					Double.parseDouble(parts[2]) };
		}
		catch (IOException | RuntimeException ignored) {
			return null; // missing/unreadable sidecar — fall back to the no-baseline note
		}
	}

	/**
	 * A one-line note pairing JMH's <em>exact</em> measured allocation
	 * ({@code gc.alloc.rate.norm}, bytes/op) with jvmlens's <em>sampled</em> totals
	 * below, so the two sit together and any divergence is visible rather than hidden
	 * (field-finding #100). Only present when the run also enabled JMH's GC profiler
	 * ({@code -prof gc}); otherwise a hint to add it.
	 */
	private static String jmhAllocNote(BenchmarkResult result) {
		Result<?> norm = result.getSecondaryResults().get("gc.alloc.rate.norm");
		if (norm == null) {
			return "> jvmlens allocation figures below are **[sampled]** estimates — add `-prof gc` "
					+ "for JMH's exact bytes/op next to them.\n\n";
		}
		String exact = humanBytesPerOp(norm.getScore());
		return "> JMH measured allocation **[exact]**: " + exact + "/op (gc.alloc.rate.norm). "
				+ "jvmlens's per-site bytes below are **[sampled]** — a gap vs this exact rate is "
				+ "expected; trust this number for the rate, jvmlens for *where*.\n\n";
	}

	/**
	 * Humanize a bytes-per-op figure (JMH reports {@code gc.alloc.rate.norm} in B/op).
	 */
	private static String humanBytesPerOp(double bytes) {
		if (bytes >= 1024.0 * 1024.0) {
			return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
		}
		if (bytes >= 1024.0) {
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		}
		return String.format(Locale.ROOT, "%.0f B", bytes);
	}

	/**
	 * Keep the fork's recording at {@code keep} (for the next run's baseline), else
	 * delete it. When kept and JMH measured exact bytes/op this run, also persist a
	 * {@code <keep>.bop} sidecar ({@code mean err forks}) so the next {@code baseline=}
	 * run can render the measured A/B verdict (#104).
	 */
	private void retainRecording(Result<?> norm, int forks) {
		try {
			if (this.keep != null) {
				Files.move(this.jfr, this.keep, StandardCopyOption.REPLACE_EXISTING);
				System.out.println("jvmlens: recording kept at " + this.keep);
				if (norm != null) {
					Files.writeString(Path.of(this.keep + ".bop"),
							norm.getScore() + " " + errOrZero(norm) + " " + forks);
				}
			}
			else {
				Files.deleteIfExists(this.jfr);
			}
		}
		catch (IOException ignored) {
			// best-effort retention/cleanup of the temp recording
		}
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
