package org.alexmond.jvmlens.jmh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.ExternalProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;

import org.alexmond.jvmlens.ProfileDiff;
import org.alexmond.jvmlens.ProfileSummary;
import org.alexmond.jvmlens.RankLimits;
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
 * {@code socketio=true} to keep them; field-finding #100), and {@code out} (a directory;
 * each benchmark's printed report is also written to {@code OUT/BenchmarkParams.id().md}
 * — see {@link ReportOut}; #199). An <em>unknown</em> option key is a hard error (a
 * {@link ProfilerException} with a did-you-mean suggestion) rather than a silent no-op —
 * a misspelled {@code appPackage} that produced an unscoped summary used to cost a whole
 * capture before you noticed (field-finding #53 item 6).
 *
 * <p>
 * When the run also enables JMH's GC profiler ({@code -prof gc}), the summary is headed
 * with JMH's <em>exact</em> {@code gc.alloc.rate.norm} (bytes/op) so the measured rate
 * sits next to jvmlens's <em>sampled</em> per-site bytes (field-finding #100). With a
 * {@code baseline=}, that becomes a measured A/B verdict (#104) plus a
 * <em>dispersion</em> verdict (#110): a real structural allocation removal collapses the
 * cross-fork variance band, and a now near-deterministic bytes/op is the loop's STOP
 * signal. A measured <em>throughput</em> A/B verdict (#112) sits alongside — the CPU
 * analog — flagging a sampled hot-path share that moved while wall-clock throughput
 * stayed flat (a CPU-share shift is not a speedup); and the {@code baseline=} comparison
 * is benchmark-matched, so a baseline recorded for a different method warns and skips
 * rather than emitting nonsense. Ships in the dependency-light {@code jvmlens-jmh.jar}
 * (engine + this profiler, no Spring/picocli/jmh) — put it and {@code jmh-core} on the
 * benchmark's classpath.
 */
public class JvmlensProfiler implements ExternalProfiler {

	/** Recognized option keys, used for the did-you-mean suggestion on a typo. */
	private static final List<String> KNOWN_KEYS = List.of("appPackage", "report", "keep", "baseline", "socketio",
			"out");

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

	/** Where {@code out=} writes each benchmark's report, or {@code null} (#199). */
	private ReportOut out;

	/** The benchmark whose forks are being counted (JMH passes the same params). */
	private BenchmarkParams trialParams;

	/**
	 * {@code afterTrial} calls so far for {@link #trialParams}, warmup forks included.
	 */
	private int forkCalls;

	/** Iterations of the measured forks so far — merged for the one final verdict. */
	private final List<IterationResult> measuredIterations = new ArrayList<>();

	/** How many measured forks the current summary merges (#191). */
	private int measuredForks = 1;

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
			case "out" -> {
				this.out = ReportOut.of(value);
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

	/**
	 * JMH calls this once per fork, warmup forks first, on a profiler instance created
	 * for this one benchmark ({@code Runner.runSeparate}); it keeps only the measured
	 * forks. So a warmup fork is skipped with a label, measured forks accumulate, and the
	 * one summary / diff / measured verdict comes after the last measured fork, over all
	 * of them with the real fork count (#191).
	 */
	@Override
	public Collection<? extends Result> afterTrial(BenchmarkResult result, long pid, File stdOut, File stdErr) {
		BenchmarkParams params = result.getParams();
		if (!params.equals(this.trialParams)) {
			this.trialParams = params;
			this.forkCalls = 0;
			this.measuredIterations.clear();
		}
		int index = this.forkCalls;
		this.forkCalls = index + 1;
		int warmupForks = params.getWarmupForks();
		if (index < warmupForks) {
			System.out.printf(Locale.ROOT,
					"%njvmlens: warmup fork %d/%d — not profiled, not counted (JMH discards it)%n", index + 1,
					warmupForks);
			deleteRecording();
			return Collections.<Result>emptyList();
		}
		int fork = index - warmupForks + 1;
		this.measuredIterations.addAll(result.getIterationResults());
		if (fork < params.getForks()) {
			System.out.printf(Locale.ROOT,
					"%njvmlens: fork %d/%d measured — one summary and verdict after fork %d/%d, over all measured "
							+ "forks%n",
					fork, params.getForks(), params.getForks(), params.getForks());
			deleteRecording();
			return Collections.<Result>emptyList();
		}
		summarize(new BenchmarkResult(params, new ArrayList<>(this.measuredIterations)), fork);
		return Collections.<Result>emptyList();
	}

	/**
	 * The one summary for this benchmark: {@code result} is every measured fork's
	 * iterations merged (as JMH's own final figure is), {@code forks} how many there
	 * were. The sampled sections come from the last fork's recording.
	 */
	private void summarize(BenchmarkResult result, int forks) {
		Result<?> norm = result.getSecondaryResults().get("gc.alloc.rate.norm");
		this.measuredForks = forks;
		try {
			String text = report(result, norm, forks);
			System.out.println(text);
			if (this.out != null) {
				this.out.write(result.getParams(), text + System.lineSeparator());
			}
		}
		finally {
			retainRecording(result, norm);
		}
	}

	/**
	 * The markdown {@link #summarize} prints (and {@code out=} writes): the multi-fork
	 * preface, then the summary or the {@code baseline=} change report — or the reason
	 * there is none, so a failed run never leaves an earlier report looking current.
	 */
	private String report(BenchmarkResult result, Result<?> norm, int forks) {
		String preface = (forks > 1) ? String.format(Locale.ROOT,
				"%n> jvmlens over %d measured forks: measured figures merge all %d; sampled sections are fork "
						+ "%d/%d's recording.%n",
				forks, forks, forks, forks) : "";
		try {
			Scope scope = Scope.of(this.appPackages, List.of());
			if (this.baseline == null) {
				return preface + "\n" + MeasuredVerdict.jmhAllocNote(result)
						+ Summarizer.summarize(this.jfr, Summarizer.Format.MARKDOWN, scope, this.report);
			}
			ProfileSummary before = RankLimits.full(() -> Summarizer.analyze(this.baseline, scope));
			ProfileSummary after = RankLimits.full(() -> Summarizer.analyze(this.jfr, scope));
			double sampledPct = (before.allocBytes() > 0)
					? 100.0 * (after.allocBytes() - before.allocBytes()) / before.allocBytes() : 0;
			return preface + "\n" + MeasuredVerdict.measuredHead(this.baseline, this.measuredForks, norm, result,
					before, after, sampledPct) + ProfileDiff.diff(before, after);
		}
		catch (IOException ex) {
			return preface + "jvmlens: could not summarize the benchmark recording: " + ex.getMessage();
		}
	}

	private void deleteRecording() {
		try {
			Files.deleteIfExists(this.jfr);
		}
		catch (IOException ignored) {
			// best-effort cleanup; the next fork's JFR dump overwrites it anyway
		}
	}

	/**
	 * Keep the fork's recording at {@code keep} (for the next run's baseline), else
	 * delete it. When kept, also persist a {@code <keep>.bop} sidecar (the benchmark,
	 * fork count, measured throughput, and exact bytes/op when {@code -prof gc} was on)
	 * so the next {@code baseline=} run can render the measured throughput (#112) +
	 * allocation (#104) A/B verdicts and detect a benchmark mismatch.
	 */
	private void retainRecording(BenchmarkResult result, Result<?> norm) {
		try {
			if (this.keep != null) {
				Files.move(this.jfr, this.keep, StandardCopyOption.REPLACE_EXISTING);
				System.out.println("jvmlens: recording kept at " + this.keep);
				Files.writeString(Path.of(this.keep + ".bop"),
						MeasuredVerdict.sidecar(result, norm, this.measuredForks));
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
