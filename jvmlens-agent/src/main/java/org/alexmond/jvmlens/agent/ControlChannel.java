package org.alexmond.jvmlens.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Watches a plain-text control file and feeds each new line to {@link AgentControl}. A
 * file (not a socket) keeps jvmlens's no-ports / no-JMX stance: an operator issues
 * commands by appending to the file over whatever access they already have ({@code ssh},
 * {@code kubectl exec}, {@code docker exec}, or the {@code jvmlens control} CLI). Polls
 * on a daemon thread; glue, so it is excluded from unit coverage (the command logic lives
 * in {@link AgentControl}).
 */
public final class ControlChannel {

	private static final long POLL_MILLIS = 1000;

	private final Path file;

	private final AgentControl control;

	private int processed;

	public ControlChannel(Path file, AgentControl control) {
		this.file = file;
		this.control = control;
	}

	/** Start polling the control file on a daemon thread. */
	public void start() {
		Thread t = new Thread(this::loop, "jvmlens-control");
		t.setDaemon(true);
		t.start();
	}

	private void loop() {
		while (true) {
			try {
				Thread.sleep(POLL_MILLIS);
				poll();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (IOException ex) {
				System.err.println("jvmlens-agent: control read failed: " + ex.getMessage());
			}
		}
	}

	private void poll() throws IOException {
		if (!Files.isReadable(this.file)) {
			return;
		}
		List<String> lines = Files.readAllLines(this.file);
		if (lines.size() < this.processed) {
			this.processed = 0; // file was truncated/rewritten — reprocess from the top
		}
		boolean applied = false;
		for (int i = this.processed; i < lines.size(); i++) {
			String result = this.control.apply(lines.get(i));
			applied = true;
			if (!result.isEmpty()) {
				System.err.println("jvmlens-agent: control: " + result);
			}
		}
		this.processed = lines.size();
		if (applied) {
			writeStatus();
		}
	}

	/**
	 * Publish the current state to a sibling {@code <file>.status} so a caller can read
	 * it back.
	 */
	private void writeStatus() throws IOException {
		Path status = this.file.resolveSibling(this.file.getFileName() + ".status");
		Files.writeString(status, this.control.status() + System.lineSeparator());
	}

}
