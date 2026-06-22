package org.alexmond.jvmlens;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
		description = "Summarize a JFR recording into an LLM-ready markdown report.")
public class AnalyzeCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<file.jfr>", description = "JFR recording to analyze.")
	Path file;

	@Override
	public Integer call() throws Exception {
		if (!Files.isReadable(file)) {
			System.err.println("jvmlens: cannot read JFR file: " + file);
			return 2;
		}
		System.out.print(Summarizer.summarize(file));
		return 0;
	}

}
