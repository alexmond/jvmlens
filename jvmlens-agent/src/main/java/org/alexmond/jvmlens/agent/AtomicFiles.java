package org.alexmond.jvmlens.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Replace a file the agent rewrites (the summary, the control status) whole, never in
 * place (#195). {@code Files.writeString} truncates the target and then writes it, so a
 * reader between the two steps sees an empty file, and a JVM exit there leaves it empty
 * for good. Instead the content goes to a temp file in the same directory, which is then
 * renamed over the target in one atomic step.
 * <p>
 * The temp file is safe in a shared directory such as {@code /tmp}: its name is random
 * and it is created with {@code CREATE_NEW} ({@code O_CREAT|O_EXCL}), which fails on any
 * existing name, a planted symbolic link included, so the host never writes through
 * another user's link. Its permissions match what the old in-place write gave: a rewrite
 * keeps the target's POSIX permissions, and a first write gets the process umask, as a
 * plain write would — never wider.
 */
final class AtomicFiles {

	private AtomicFiles() {
	}

	/**
	 * Write {@code content} to {@code target} so readers see the old file or the new one,
	 * never a partial one.
	 * <p>
	 * Falls back to the old in-place write when no temp file can be created or the rename
	 * fails (a read-only directory, or a target that is a mount point such as a single
	 * file mounted into a container), and when the target is itself a symbolic link,
	 * which the old write followed and a rename would replace. The agent keeps publishing
	 * summaries; it only loses the atomicity.
	 * @param target the file to replace
	 * @param content the new content
	 * @throws IOException if neither the atomic replace nor the fallback write works
	 */
	static void writeString(Path target, String content) throws IOException {
		if (Files.isSymbolicLink(target)) {
			Files.writeString(target, content);
			return;
		}
		Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
		boolean created = false;
		try {
			try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				created = true;
				os.write(content.getBytes(StandardCharsets.UTF_8));
			}
			copyPermissions(target, tmp);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			created = false;
		}
		catch (IOException ex) {
			Files.writeString(target, content);
		}
		finally {
			if (created) {
				deleteQuietly(tmp);
			}
		}
	}

	/** Give the temp file the target's POSIX permissions, when there is a target. */
	private static void copyPermissions(Path target, Path tmp) throws IOException {
		if (!Files.exists(target)) {
			return; // first write: CREATE_NEW already applied the umask, as a plain write
		}
		try {
			Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target));
		}
		catch (UnsupportedOperationException ignored) {
			// not a POSIX file system: nothing to carry over
		}
	}

	private static void deleteQuietly(Path tmp) {
		try {
			Files.deleteIfExists(tmp);
		}
		catch (IOException ignored) {
			// best-effort: a stray temp file must not stop the summary
		}
	}

}
