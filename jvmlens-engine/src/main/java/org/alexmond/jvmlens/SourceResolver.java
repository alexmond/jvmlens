package org.alexmond.jvmlens;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * Enriches a {@link ProfileSummary}'s {@code file:line} anchors (#87) with the actual
 * source-line text, when source roots are available — so a reader (or coding agent) sees
 * {@code Svc.compute:88 ⟶ for (byte b : d) sb.append(…)} without opening the file
 * (field-finding #100 item 3). Opt-in via {@code analyze --source}; degrades silently
 * when a file isn't found, so it works offline and never fabricates. Decorates the
 * allocation-site and hot-leaf rows (the ones whose name is the method and whose teaser
 * carries a single line).
 */
final class SourceResolver {

	/**
	 * Allocation-site teaser starts with the call-site line, e.g.
	 * {@code :129 · byte[] …}.
	 */
	private static final Pattern ALLOC_LINE = Pattern.compile("^:(\\d+)\\b");

	/** Hot-leaf teaser is the dominant line, e.g. {@code line 129}. */
	private static final Pattern LEAF_LINE = Pattern.compile("^line (\\d+)$");

	private static final int MAX_SRC_LEN = 100;

	private final List<Path> roots;

	private SourceResolver(List<Path> roots) {
		this.roots = roots;
	}

	/**
	 * Parse a comma- or path-separator-delimited list of source roots ({@code []} =
	 * disabled).
	 */
	static List<Path> roots(String spec) {
		List<Path> out = new ArrayList<>();
		if (spec != null && !spec.isBlank()) {
			for (String s : spec.split("[," + Pattern.quote(File.pathSeparator) + "]")) {
				if (!s.isBlank()) {
					out.add(Path.of(s.trim()));
				}
			}
		}
		return out;
	}

	/**
	 * A copy of {@code s} with allocation-site and hot-leaf teasers enriched with their
	 * source-line text; returns {@code s} unchanged when {@code roots} is empty or
	 * nothing resolves.
	 */
	static ProfileSummary decorate(ProfileSummary s, List<Path> roots) {
		if (roots.isEmpty()) {
			return s;
		}
		SourceResolver r = new SourceResolver(roots);
		List<Ranked> leaves = r.decorateRows(s.hotLeaves(), LEAF_LINE);
		List<Ranked> alloc = r.decorateRows(s.allocSites(), ALLOC_LINE);
		if (leaves == null && alloc == null) {
			return s;
		}
		return new ProfileSummary(s.source(), s.execSamples(), s.allocTypes(), s.oldObjects(), s.gcPauses(),
				s.gcPauseMillis(), s.hotPaths(), (leaves != null) ? leaves : s.hotLeaves(),
				(alloc != null) ? alloc : s.allocSites(), s.allocatedTypes(), s.locks(), s.monitors(), s.cause(),
				s.appPackage(), s.sections(), s.allocBytes(), s.allocSamples());
	}

	/**
	 * Returns a decorated copy, or {@code null} when no row resolved (nothing changed).
	 */
	private List<Ranked> decorateRows(List<Ranked> rows, Pattern linePattern) {
		List<Ranked> out = new ArrayList<>(rows.size());
		boolean changed = false;
		for (Ranked row : rows) {
			String src = (row.stack() != null) ? resolve(row.name(), row.stack(), linePattern) : null;
			if (src != null) {
				out.add(new Ranked(row.name(), row.share(), row.count(), row.stack() + " ⟶ " + src));
				changed = true;
			}
			else {
				out.add(row);
			}
		}
		return changed ? out : null;
	}

	private String resolve(String method, String teaser, Pattern linePattern) {
		Matcher m = linePattern.matcher(teaser);
		if (!m.find()) {
			return null;
		}
		int dot = method.lastIndexOf('.');
		String fqType = (dot > 0) ? method.substring(0, dot) : method;
		int dollar = fqType.indexOf('$');
		String relPath = ((dollar > 0) ? fqType.substring(0, dollar) : fqType).replace('.', '/') + ".java";
		int line = Integer.parseInt(m.group(1));
		for (Path root : this.roots) {
			String text = readLine(root.resolve(relPath), line);
			if (text != null) {
				return text;
			}
		}
		return null;
	}

	private static String readLine(Path file, int line) {
		if (line <= 0 || !Files.isReadable(file)) {
			return null;
		}
		try {
			List<String> lines = Files.readAllLines(file);
			if (line > lines.size()) {
				return null;
			}
			String t = lines.get(line - 1).trim();
			if (t.isEmpty()) {
				return null;
			}
			return (t.length() <= MAX_SRC_LEN) ? t : t.substring(0, MAX_SRC_LEN - 1) + "…";
		}
		catch (IOException | RuntimeException ignored) {
			return null;
		}
	}

}
