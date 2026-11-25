package org.alexmond.jvmlens.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Replace a file the agent rewrites (the summary, the control status) whole, never in
 * place (#195). {@code Files.writeString} truncates the target and then writes it, so a
 * reader between the two steps sees an empty file, and a JVM exit there leaves it empty
 * for good. Instead the content goes to a temp file in the same directory, which is then
 * renamed over the target in one atomic step.
 * <p>
 * Safe in a shared directory such as {@code /tmp}, where another local user can plant
 * links, since the agent writes with the host's privileges:
 * <ul>
 * <li>the temp name is random and created with {@code CREATE_NEW}
 * ({@code O_CREAT|O_EXCL}), which fails on any existing name, a link included;</li>
 * <li>a link at the target is never written through: the rename replaces the link itself,
 * and the in-place fallback opens with {@code NOFOLLOW_LINKS} and refuses;</li>
 * <li>a rewrite creates the temp with the target's POSIX permissions already set, so new
 * content is never in a file wider than the target. A first write gets the process umask,
 * as a plain write would.</li>
 * </ul>
 */
final class AtomicFiles {

	private static final Consumer<Path> NO_HOOK = (Path tmp) -> {
	};

	private AtomicFiles() {
	}

	/**
	 * Write {@code content} to {@code target} so readers see the old file or the new one,
	 * never a partial one.
	 * <p>
	 * Falls back to an in-place write when no temp file can be created or the rename
	 * fails (a read-only directory, or a target that is a mount point such as a single
	 * file mounted into a container). The agent keeps publishing summaries; it only loses
	 * the atomicity. The fallback never follows a symbolic link at the target: it throws
	 * instead, which the agent reports as a warning.
	 * @param target the file to replace
	 * @param content the new content
	 * @throws IOException if neither the atomic replace nor the fallback write works
	 */
	static void writeString(Path target, String content) throws IOException {
		writeString(target, content, NO_HOOK);
	}

	/**
	 * As {@link #writeString(Path, String)}, calling {@code beforeWrite} with the temp
	 * file once it is created and its permissions set, before any content goes in. A test
	 * seam: it lets a test observe what the temp file looks like at that moment.
	 */
	static void writeString(Path target, String content, Consumer<Path> beforeWrite) throws IOException {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
		boolean created = false;
		try {
			Set<PosixFilePermission> perms = regularFilePermissions(target);
			try (SeekableByteChannel channel = createTemp(tmp, perms)) {
				created = true;
				if (perms != null && !perms.equals(Files.getPosixFilePermissions(tmp))) {
					// the umask narrowed the creation attribute: widen back to the
					// target's permissions while the file is still empty
					Files.setPosixFilePermissions(tmp, perms);
				}
				beforeWrite.accept(tmp);
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			created = false;
		}
		catch (IOException ex) {
			writeInPlace(target, bytes);
		}
		finally {
			if (created) {
				deleteQuietly(tmp);
			}
		}
	}

	/**
	 * The target's POSIX permissions when it is a regular file (not followed through a
	 * link: a link's own mode is {@code rwxrwxrwx}), else {@code null} — no target yet, a
	 * link, or a file system without POSIX permissions.
	 */
	private static Set<PosixFilePermission> regularFilePermissions(Path target) throws IOException {
		try {
			BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			return attrs.isRegularFile() ? Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS) : null;
		}
		catch (NoSuchFileException | UnsupportedOperationException ignored) {
			return null;
		}
	}

	/** Create the temp file, with {@code perms} set at creation when there are any. */
	private static SeekableByteChannel createTemp(Path tmp, Set<PosixFilePermission> perms) throws IOException {
		Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		if (perms == null) {
			return Files.newByteChannel(tmp, options);
		}
		return Files.newByteChannel(tmp, options, PosixFilePermissions.asFileAttribute(perms));
	}

	/** The non-atomic fallback: truncate and write, but never through a link. */
	private static void writeInPlace(Path target, byte[] bytes) throws IOException {
		if (Files.isSymbolicLink(target)) {
			throw new IOException("refusing to write through symbolic link " + target);
		}
		// NOFOLLOW_LINKS closes the race with a link planted after the check above
		try (OutputStream os = Files.newOutputStream(target, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			os.write(bytes);
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
