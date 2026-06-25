package org.alexmond.jvmlens;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
		description = "Summarize a JFR recording into an LLM-ready report (or diff two recordings).")
public class AnalyzeCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<file.jfr>",
			description = "JFR recording to analyze (the 'after' when diffing).")
	Path file;

	@Option(names = { "-b", "--baseline" }, paramLabel = "<before.jfr>",
			description = "Diff <file.jfr> against this baseline recording: name what changed (the optimize→measure loop).")
	Path baseline;

	@Mixin
	OutputOptions output;

	@Override
	public Integer call() throws Exception {
		if (!Files.isReadable(file)) {
			System.err.println("jvmlens: cannot read JFR file: " + file);
			return 2;
		}
		if (baseline != null) {
			if (!Files.isReadable(baseline)) {
				System.err.println("jvmlens: cannot read baseline JFR file: " + baseline);
				return 2;
			}
			String delta = ProfileDiff.diff(Summarizer.analyze(baseline, output.scope()),
					Summarizer.analyze(file, output.scope()));
			System.out.print((output.format == Summarizer.Format.PROMPT) ? Renderers.promptOf(delta) : delta);
			return 0;
		}
		System.out.print(Summarizer.summarize(file, output.format, output.scope(), output.report));
		return 0;
	}

}
