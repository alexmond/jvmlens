package org.alexmond.jvmlens;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
		description = "Summarize a JFR recording (or a JMH -prof jfr directory) into an LLM-ready report, "
				+ "or diff two recordings.")
public class AnalyzeCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<file.jfr|dir>",
			description = "A JFR recording, or a directory of them (a JMH -prof jfr run, merged); the 'after' when diffing.")
	Path file;

	@Option(names = { "-b", "--baseline" }, paramLabel = "<before.jfr|dir>",
			description = "Diff <file.jfr|dir> against this baseline: name what changed (the optimize→measure loop).")
	Path baseline;

	@Option(names = { "--assert" }, paramLabel = "<rules>",
			description = "CI perf-gate over the diff (needs --baseline): `metric < n` rules, comma-separated "
					+ "(gc-ms, gc-pct, alloc-pct, oldobj-delta, regression-pp, new-hotpath-pp). Non-zero exit on regression.")
	String assertSpec;

	@Option(names = { "--ops" }, paramLabel = "<before,after>",
			description = "Divide diff totals by an operations count per side (needs --baseline) for per-op figures "
					+ "comparable across runs of different throughput — e.g. a JMH cross-JDK sweep, where a faster "
					+ "JVM does more ops per fixed-duration iteration. Two positive integers, e.g. `--ops 4200000,5100000`.")
	String ops;

	@Option(names = { "--hints" },
			description = "Append a hedged `[possible]` fix-direction section (off by default — keeps output clean-data-only).")
	boolean hints;

	@Option(names = { "--top-k" }, paramLabel = "<n>",
			description = "Keep only the top <n> rows per section (budget-dial the summary size).")
	Integer topK;

	@Option(names = { "--max-tokens" }, paramLabel = "<n>",
			description = "Shrink top-k until the summary fits roughly <n> tokens (chars/4).")
	Integer maxTokens;

	@Option(names = { "--skip-warmup" }, paramLabel = "<ms>",
			description = "Drop samples from the first <ms> of each recording, so hot paths reflect steady "
					+ "state, not JIT/classload warmup (useful for `profile`/JMH fresh-JVM captures).")
	Integer skipWarmup;

	@Option(names = { "--source" }, paramLabel = "<dir>",
			description = "Source root(s) (comma-separated) to echo the line text at each file:line "
					+ "anchor, e.g. `src/main/java`; off by default.")
	String sourceRoots;

	@Option(names = { "--per-recording" },
			description = "For a merged JMH -prof jfr directory, add a per-recording breakdown (each .jfr's "
					+ "sample count + dominant hot paths), so you see which recording a hot path concentrates "
					+ "in without a second single-file pass. Multi-file only.")
	boolean perRecording;

	@Mixin
	OutputOptions output;

	@Override
	public Integer call() throws Exception {
		List<Path> afterFiles = readable(file);
		if (afterFiles.isEmpty()) {
			System.err.println("jvmlens: no readable .jfr at: " + file);
			return 2;
		}
		if (baseline != null) {
			List<Path> beforeFiles = readable(baseline);
			if (beforeFiles.isEmpty()) {
				System.err.println("jvmlens: no readable baseline .jfr at: " + baseline);
				return 2;
			}
			ProfileSummary before = Summarizer.analyze(beforeFiles, output.scope(),
					labeled(Recordings.label(baseline, file)), warmupMs());
			ProfileSummary after = Summarizer.analyze(afterFiles, output.scope(),
					labeled(Recordings.label(file, baseline)), warmupMs());
			long[] opsPair = null;
			if (ops != null) {
				opsPair = parseOps(ops);
				if (opsPair == null) {
					return 2; // parseOps reported the error
				}
			}
			String delta = (opsPair != null) ? ProfileDiff.diff(before, after, opsPair[0], opsPair[1])
					: ProfileDiff.diff(before, after);
			if (assertSpec != null) {
				PerfGate.Result gate = PerfGate.evaluate(before, after, assertSpec);
				System.out.print(delta + "\n" + gate.report());
				return gate.passed() ? 0 : 1;
			}
			System.out.print((output.format == Summarizer.Format.PROMPT) ? Renderers.promptOf(delta) : delta);
			return 0;
		}
		if (assertSpec != null) {
			System.err.println("jvmlens: --assert needs --baseline (it gates a before→after diff)");
			return 2;
		}
		if (ops != null) {
			System.err.println("jvmlens: --ops needs --baseline (it normalizes a before→after diff)");
			return 2;
		}
		if (topK != null && topK > 0) {
			RankLimits.set("all", topK);
		}
		if (maxTokens != null && topK == null) {
			System.out.print(withinBudget(afterFiles, labeled(Recordings.label(file, null)), maxTokens));
			return 0;
		}
		ProfileSummary summary = withSource(Summarizer.analyze(afterFiles, output.scope(),
				labeled(Recordings.label(file, null)), warmupMs(), perRecording));
		System.out.print(render(summary));
		return 0;
	}

	/**
	 * Enrich {@code file:line} anchors with their source text when {@code --source} is
	 * set.
	 */
	private ProfileSummary withSource(ProfileSummary summary) {
		return SourceResolver.decorate(summary, SourceResolver.roots(this.sourceRoots));
	}

	/** The warmup window to drop, in ms (0 = keep everything). */
	private long warmupMs() {
		return (skipWarmup != null && skipWarmup > 0) ? skipWarmup : 0L;
	}

	/**
	 * Annotate the summary's source label when a warmup window was skipped (trust
	 * signal).
	 */
	private String labeled(String base) {
		return (warmupMs() > 0) ? base + " (warmup " + warmupMs() + "ms skipped)" : base;
	}

	/** Render at the largest top-k whose output fits {@code maxTokens} (~chars/4). */
	private String withinBudget(List<Path> files, String label, int maxTokens) throws java.io.IOException {
		String out = "";
		for (int k : new int[] { RankLimits.DEFAULT, 4, 3, 2, 1 }) {
			RankLimits.set("all", k);
			out = render(withSource(Summarizer.analyze(files, output.scope(), label, warmupMs(), perRecording)));
			if (out.length() / 4 <= maxTokens) {
				break;
			}
		}
		return out;
	}

	/**
	 * Render the summary, appending hedged fix hints when {@code --hints} is set
	 * (md/prompt).
	 */
	private String render(ProfileSummary summary) {
		if (hints && output.format != Summarizer.Format.JSON) {
			String body = Summarizer.render(summary, Summarizer.Format.MARKDOWN, output.report)
					+ FixHints.render(summary);
			return (output.format == Summarizer.Format.PROMPT) ? Renderers.promptOf(body) : body;
		}
		return Summarizer.render(summary, output.format, output.report);
	}

	/**
	 * Parse {@code --ops <before,after>} into {@code [before, after]}, or {@code null}
	 * (after printing the reason) when malformed or non-positive.
	 */
	private static long[] parseOps(String spec) {
		String[] parts = spec.split(",");
		if (parts.length != 2) {
			System.err.println("jvmlens: --ops must be <before,after>, e.g. 4200000,5100000: " + spec);
			return null;
		}
		try {
			long before = Long.parseLong(parts[0].trim());
			long after = Long.parseLong(parts[1].trim());
			if (before <= 0 || after <= 0) {
				System.err.println("jvmlens: --ops values must be positive: " + spec);
				return null;
			}
			return new long[] { before, after };
		}
		catch (NumberFormatException ex) {
			System.err.println("jvmlens: --ops must be two integers <before,after>: " + spec);
			return null;
		}
	}

	private static List<Path> readable(Path arg) throws java.io.IOException {
		return Recordings.expand(arg).stream().filter(Files::isReadable).toList();
	}

}
