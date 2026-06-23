package org.alexmond.jvmlens;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
		description = "Summarize a JFR recording into an LLM-ready report.")
public class AnalyzeCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<file.jfr>", description = "JFR recording to analyze.")
	Path file;

	@Option(names = { "-f", "--format" }, paramLabel = "<format>",
			description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
	Summarizer.Format format = Summarizer.Format.MARKDOWN;

	@Option(names = { "-a", "--app-package" }, paramLabel = "<prefix>", split = ",",
			description = "Treat only these package prefixes as application code (repeatable).")
	List<String> appPackages = new ArrayList<>();

	@Option(names = { "-x", "--exclude" }, paramLabel = "<prefix>", split = ",",
			description = "Extra package prefixes to treat as non-application code (repeatable).")
	List<String> excludePackages = new ArrayList<>();

	@Override
	public Integer call() throws Exception {
		if (!Files.isReadable(file)) {
			System.err.println("jvmlens: cannot read JFR file: " + file);
			return 2;
		}
		System.out.print(Summarizer.summarize(file, format, Scope.of(appPackages, excludePackages)));
		return 0;
	}

}
