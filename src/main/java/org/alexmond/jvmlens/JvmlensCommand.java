package org.alexmond.jvmlens;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Component
@Command(name = "jvmlens", mixinStandardHelpOptions = true, version = "jvmlens 0.1.0",
		description = "Turn JVM runtime evidence into a compact, LLM-ready diagnosis.",
		subcommands = { AnalyzeCommand.class, ProfileCommand.class, WatchCommand.class, McpServerCommand.class })
public class JvmlensCommand implements Runnable {

	@Spec
	CommandSpec spec;

	@Override
	public void run() {
		spec.commandLine().usage(System.out);
	}

}
