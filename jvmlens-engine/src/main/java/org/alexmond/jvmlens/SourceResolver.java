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
import org.alexmond.jvmlens.ProfileSummary.Section;

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

	/**
	 * An extended-section row (db/web/cache/messaging) carries its captured app call-site
	 * as {@code … at com.acme.OrderRepo:88}; group 1 is the fully-qualified type, group 2
	 * the line — so the path comes from the anchor, not the row name (which is the SQL /
	 * endpoint shape).
	 */
	private static final Pattern SECTION_ANCHOR = Pattern.compile("\\bat ([\\w.$]+):(\\d+)");

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
		List<Section> sections = r.decorateSections(s.sections());
		if (leaves == null && alloc == null && sections == null) {
			return s;
		}
		return new ProfileSummary(s.source(), s.execSamples(), s.allocTypes(), s.oldObjects(), s.gcPauses(),
				s.gcPauseMillis(), s.hotPaths(), (leaves != null) ? leaves : s.hotLeaves(),
				(alloc != null) ? alloc : s.allocSites(), s.allocatedTypes(), s.locks(), s.monitors(), s.cause(),
				s.appPackage(), (sections != null) ? sections : s.sections(), s.allocBytes(), s.allocSamples());
	}

	/**
	 * Returns a copy of {@code sections} whose rows carrying an {@code at <fqn>:<line>}
	 * anchor are enriched with the source-line text, or {@code null} when nothing
	 * resolved.
	 */
	private List<Section> decorateSections(List<Section> sections) {
		List<Section> out = new ArrayList<>(sections.size());
		boolean changed = false;
		for (Section sec : sections) {
			List<Ranked> rows = new ArrayList<>(sec.rows().size());
			boolean secChanged = false;
			for (Ranked row : sec.rows()) {
				String src = (row.stack() != null) ? resolveAnchor(row.stack()) : null;
				if (src != null) {
					rows.add(new Ranked(row.name(), row.share(), row.count(), row.stack() + " ⟶ " + src));
					secChanged = true;
				}
				else {
					rows.add(row);
				}
			}
			out.add(secChanged ? new Section(sec.key(), sec.title(), sec.unit(), sec.measured(), rows) : sec);
			changed = changed || secChanged;
		}
		return changed ? out : null;
	}

	/**
	 * Resolve an {@code at <fqn>:<line>} anchor (the type is the anchor's, not the
	 * row's).
	 */
	private String resolveAnchor(String teaser) {
		Matcher m = SECTION_ANCHOR.matcher(teaser);
		if (!m.find()) {
			return null;
		}
		String fqType = m.group(1);
		int dollar = fqType.indexOf('$');
		String relPath = ((dollar > 0) ? fqType.substring(0, dollar) : fqType).replace('.', '/') + ".java";
		int line = Integer.parseInt(m.group(2));
		for (Path root : this.roots) {
			String text = readLine(root.resolve(relPath), line);
			if (text != null) {
				return text;
			}
		}
		return null;
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
