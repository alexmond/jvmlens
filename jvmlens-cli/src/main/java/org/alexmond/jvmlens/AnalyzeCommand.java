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
			String delta = ProfileDiff.diff(before, after);
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
		if (topK != null && topK > 0) {
			RankLimits.set("all", topK);
		}
		if (maxTokens != null && topK == null) {
			System.out.print(withinBudget(afterFiles, labeled(Recordings.label(file, null)), maxTokens));
			return 0;
		}
		ProfileSummary summary = withSource(
				Summarizer.analyze(afterFiles, output.scope(), labeled(Recordings.label(file, null)), warmupMs()));
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
			out = render(withSource(Summarizer.analyze(files, output.scope(), label, warmupMs())));
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

	private static List<Path> readable(Path arg) throws java.io.IOException {
		return Recordings.expand(arg).stream().filter(Files::isReadable).toList();
	}

}
