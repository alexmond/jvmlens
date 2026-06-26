package org.alexmond.jvmlens;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Issues an in-flight command to a running jvmlens agent by appending it to the agent's
 * control file (the agent watches it). Run this on the target's host through the access
 * channel you already have — e.g.
 * {@code kubectl exec pod -- java -jar jvmlens.jar control
 * /agent/jvmlens.control stop}. No ports, no JMX.
 */
@Component
@Command(name = "control", mixinStandardHelpOptions = true,
		description = "Send an in-flight command to a running agent via its control file "
				+ "(start|stop|clear|dump|enable <dim>|disable <dim>|settings <profile|default>|"
				+ "interval <n>|scope app|exclude <prefix>|scope reset|status).")
public class ControlCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<control-file>",
			description = "The agent's control file (its `control=` path).")
	Path file;

	@Parameters(index = "1..*", paramLabel = "<command>", description = "The command words, e.g. `enable db`.")
	List<String> command;

	@Override
	public Integer call() throws Exception {
		if (this.command == null || this.command.isEmpty()) {
			System.err.println("jvmlens: a command is required, e.g. `control <file> stop`");
			return 2;
		}
		Path status = this.file.resolveSibling(this.file.getFileName() + ".status");
		long before = Files.isReadable(status) ? Files.getLastModifiedTime(status).toMillis() : 0;
		String line = String.join(" ", this.command) + System.lineSeparator();
		Files.writeString(this.file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
		System.out.println("jvmlens: sent `" + String.join(" ", this.command) + "` -> " + this.file);
		readBack(status, before);
		return 0;
	}

	/**
	 * Best-effort: wait briefly for the agent to refresh its status file and print it.
	 */
	private static void readBack(Path status, long before) throws Exception {
		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline) {
			if (Files.isReadable(status) && Files.getLastModifiedTime(status).toMillis() > before) {
				System.out.println("agent: " + Files.readString(status).strip());
				return;
			}
			Thread.sleep(150);
		}
	}

}
