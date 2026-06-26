package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * A small, conservative catalog mapping recognized hot-frame / allocation shapes to a
 * one-line fix <em>direction</em>. Strictly hedged — every line is tagged
 * {@code [possible]} and grounded in the row that triggered it; jvmlens still leads with
 * clean data, so this is <strong>opt-in</strong> ({@code analyze --hints}). Per the
 * gotmpl4j field-finding (#39, gap 5): it would have named both that run's hot spots
 * (float→string formatting, per-range iterator allocation).
 */
public final class FixHints {

	private static final List<Rule> RULES = List.of(
			rule("DoubleToDecimal|FloatingDecimal|Double\\.toString|formatUnsignedInt|BigDecimal.*toString",
					"number→string formatting — consider a direct/cached formatter"),
			rule("java\\.math\\.BigDecimal|java\\.math\\.BigInteger",
					"BigDecimal/BigInteger math — use primitive arithmetic where precision allows"),
			rule("\\$ListItr|\\$Itr\\b|LinkedList\\$|Iterator\\.<init>|iterator\\b",
					"per-iteration iterator allocation — prefer an indexed loop or reuse the iterator"),
			rule("ensureCapacity|AbstractStringBuilder|StringBuilder",
					"StringBuilder growth — presize the buffer to the expected length"),
			rule("Pattern\\.(compile|matcher)|regex", "regex in a hot path — precompile and reuse the Pattern"),
			rule("HashMap.*resize|ArrayList.*grow|Arrays\\.copyOf|hashtable.*rehash",
					"collection/array resize or copy — presize or reuse the backing store"),
			rule("Integer\\.valueOf|Long\\.valueOf|Double\\.valueOf|\\.intValue\\(\\)",
					"autoboxing in a hot path — prefer primitives / primitive collections"),
			rule("java\\.lang\\.reflect\\.(Method|Field|Constructor)|MethodHandle\\.invoke",
					"reflective dispatch — cache the handle or call directly"),
			rule("String\\.format|Formatter\\b", "String.format in a hot path — prefer concatenation / StringBuilder"));

	private FixHints() {
	}

	private static Rule rule(String regex, String hint) {
		return new Rule(Pattern.compile(regex), hint);
	}

	/**
	 * The hedged fix directions for a summary — one per matched shape, each grounded in
	 * the row that triggered it, deduplicated.
	 * @param s the summary to scan
	 * @return the hint lines (possibly empty)
	 */
	public static List<String> hints(ProfileSummary s) {
		Map<String, String> byHint = new LinkedHashMap<>();
		for (Ranked r : rows(s)) {
			String text = r.name() + " " + ((r.stack() != null) ? r.stack() : "");
			for (Rule rule : RULES) {
				if (rule.pattern().matcher(text).find()) {
					byHint.putIfAbsent(rule.hint(), r.name());
				}
			}
		}
		List<String> out = new ArrayList<>();
		byHint.forEach((hint, where) -> out.add(hint + " (`" + where + "`)"));
		return out;
	}

	/** The hints as a markdown section, or empty when nothing matched. */
	public static String render(ProfileSummary s) {
		List<String> hints = hints(s);
		if (hints.isEmpty()) {
			return "";
		}
		StringBuilder md = new StringBuilder("## Likely fix directions [possible]\n");
		hints.forEach((h) -> md.append("- ").append(h).append('\n'));
		return md.append('\n').toString();
	}

	private static List<Ranked> rows(ProfileSummary s) {
		List<Ranked> all = new ArrayList<>(s.hotPaths());
		all.addAll(s.hotLeaves());
		all.addAll(s.allocSites());
		all.addAll(s.allocatedTypes());
		all.addAll(s.locks());
		return all;
	}

	private record Rule(Pattern pattern, String hint) {
	}

}
