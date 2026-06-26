package org.alexmond.jvmlens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves a recording argument that may be a single {@code .jfr} file <em>or a
 * directory</em> — the latter being a JMH {@code -prof jfr} output, where every fork
 * writes its own {@code .jfr} (often all named {@code profile.jfr} in per-benchmark
 * subdirs). A directory expands to all the {@code .jfr} files under it, which
 * {@link Summarizer} merges into one summary so the signal isn't split across forks.
 */
public final class Recordings {

	private Recordings() {
	}

	/**
	 * The {@code .jfr} files for an argument: the file itself, or every {@code .jfr}
	 * under a directory (recursively, sorted for stable ordering).
	 * @param path a {@code .jfr} file or a directory of them
	 * @return the recordings to read (possibly empty for a directory with none)
	 * @throws IOException if the directory cannot be walked
	 */
	public static List<Path> expand(Path path) throws IOException {
		if (!Files.isDirectory(path)) {
			return List.of(path);
		}
		try (Stream<Path> walk = Files.walk(path)) {
			return walk.filter((p) -> p.toString().endsWith(".jfr") && Files.isRegularFile(p)).sorted().toList();
		}
	}

	/**
	 * A display label for a recording argument — its file/dir name, or, when two
	 * arguments collide on name (JMH's {@code profile.jfr} per fork), its
	 * {@code parent/name} so a diff header stays unambiguous.
	 * @param path the argument
	 * @param other the other argument being compared, or {@code null}
	 * @return the label
	 */
	public static String label(Path path, Path other) {
		String name = String.valueOf(path.getFileName());
		if (other != null && name.equals(String.valueOf(other.getFileName())) && path.getParent() != null) {
			return path.getParent().getFileName() + "/" + name;
		}
		return name;
	}

}
