package org.alexmond.jvmlens.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replace a file the agent rewrites (the summary, the control status) whole, never in
 * place (#195). {@code Files.writeString} truncates the target and then writes it, so a
 * reader between the two steps sees an empty file, and a JVM exit there leaves it empty
 * for good. Instead the content goes to a sibling {@code <name>.tmp} first, which is then
 * renamed over the target in one atomic step.
 */
final class AtomicFiles {

	private AtomicFiles() {
	}

	/**
	 * Write {@code content} to {@code target} so readers see the old file or the new one,
	 * never a partial one.
	 * <p>
	 * When the rename fails (a target that is a mount point, such as a single file
	 * mounted into a container, refuses to be renamed over), this falls back to the old
	 * in-place write. The agent keeps publishing summaries; it only loses the atomicity.
	 * @param target the file to replace
	 * @param content the new content
	 * @throws IOException if neither the atomic replace nor the fallback write works
	 */
	static void writeString(Path target, String content) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			Files.writeString(tmp, content);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			deleteQuietly(tmp);
			Files.writeString(target, content);
		}
	}

	private static void deleteQuietly(Path tmp) {
		try {
			Files.deleteIfExists(tmp);
		}
		catch (IOException ignored) {
			// best-effort: a stray .tmp must not stop the fallback write
		}
	}

}
