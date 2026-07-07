package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * A small, conservative catalog mapping recognized hot-frame / allocation shapes to a
 * one-line fix <em>direction</em>. Strictly hedged — every line is tagged
 * {@code [possible]} and grounded in the row that triggered it; jvmlens still leads with
 * clean data, so this is <strong>opt-in</strong> ({@code analyze --hints}). Per the
 * gotmpl4j field-finding (#39, gap 5): it would have named both that run's hot spots
 * (float→string formatting, per-range iterator allocation).
 *
 * <p>
 * Each direction is also classified by {@link Lever}: <strong>structural</strong> levers
 * (per-iteration iterator/lambda allocation, un-presized buffers, reflection) are
 * mechanical and safe to pull first; <strong>inherent</strong> ones (number→string
 * formatting, BigDecimal precision) are parity-sensitive and shouldn't be churned without
 * verifying output. This is the judgment the gotmpl4j loop actually needed (#53 item 2):
 * {@code floatString} was #1 by every metric but the <em>wrong</em> lever (inherent),
 * while the #2 {@code ListNode.iterator} (structural) was the right one.
 */
public final class FixHints {

	/** Whether a fix direction is a mechanical/safe lever or a parity-sensitive one. */
	private enum Lever {

		/** Mechanical — safe to pull first (iterator/lambda alloc, presize, reflect). */
		STRUCTURAL("structural"),
		/** Parity-sensitive — verify output before churning (formatting, precision). */
		INHERENT("inherent");

		private final String tag;

		Lever(String tag) {
			this.tag = tag;
		}

	}

	private static final List<Rule> RULES = List.of(
			rule("DoubleToDecimal|FloatingDecimal|Double\\.toString|formatUnsignedInt|BigDecimal.*toString",
					Lever.INHERENT, "number→string formatting — consider a direct/cached formatter"),
			rule("java\\.math\\.BigDecimal|java\\.math\\.BigInteger", Lever.INHERENT,
					"BigDecimal/BigInteger math — use primitive arithmetic where precision allows"),
			rule("\\$ListItr|\\$Itr\\b|LinkedList\\$|Iterator\\.<init>|iterator\\b", Lever.STRUCTURAL,
					"per-iteration iterator allocation — prefer an indexed loop or reuse the iterator"),
			rule("\\$\\$Lambda", Lever.STRUCTURAL,
					"lambda captured per call in a hot path — hoist to a static/cached instance or pass primitives "
							+ "(if it escapes; C2 scalar-replaces non-escaping captures — confirm with -prof gc)"),
			rule("ensureCapacity|AbstractStringBuilder|StringBuilder", Lever.STRUCTURAL,
					"StringBuilder growth — presize the buffer to the expected length"),
			rule("java\\.util\\.regex|Pattern\\.(compile|matcher|clazz|expr)|\\.(replaceAll|replaceFirst|matches|split)\\(",
					Lever.STRUCTURAL,
					"regex compiled per call — hoist the Pattern to a `static final` field and reuse Matcher "
							+ "(String.replaceAll(String,…)/matches/split/replaceFirst recompile the regex every call)"),
			rule("HashMap.*resize|ArrayList.*grow|Arrays\\.copyOf|hashtable.*rehash", Lever.STRUCTURAL,
					"collection/array resize or copy — presize or reuse the backing store"),
			rule("Integer\\.valueOf|Long\\.valueOf|Double\\.valueOf|\\.intValue\\(\\)", Lever.STRUCTURAL,
					"autoboxing in a hot path — prefer primitives / primitive collections (only if the box escapes; "
							+ "C2 scalar-replaces non-escaping boxes, so verify the win with -prof gc)"),
			rule("java\\.lang\\.reflect\\.(Method|Field|Constructor)|MethodHandle\\.invoke", Lever.STRUCTURAL,
					"reflective dispatch — cache the handle or call directly"),
			rule("String\\.format|Formatter\\b", Lever.STRUCTURAL,
					"String.format in a hot path — prefer concatenation / StringBuilder"),
			// Section-scoped rules — matched only against their own extended section's
			// rows,
			// so SQL / endpoint text can never trip a code rule and vice-versa.
			sectionRule("db", "possible N\\+1|high call count", Lever.STRUCTURAL,
					"N+1 query — the same shape runs many times per request; batch into one round-trip "
							+ "(JPA `@BatchSize`/join-fetch, or a single `WHERE id IN (…)`)"),
			sectionRule("db", "(?i)^select\\s+\\*", Lever.STRUCTURAL,
					"`SELECT *` — project only the columns you use (cuts row width, wire bytes, and "
							+ "lets covering indexes apply)"),
			sectionRule("web", "high error rate", Lever.STRUCTURAL,
					"endpoint has a high error rate — validate inputs and handle downstream failures "
							+ "before the expensive work (the anchor points at the handler)"));

	private static final String HEADER = "## Likely fix directions [possible]\n"
			+ "> `[structural]` = mechanical, safe to pull first · `[inherent]` = "
			+ "parity-sensitive (formatting/precision), verify output before changing\n";

	private FixHints() {
	}

	private static Rule rule(String regex, Lever lever, String hint) {
		return new Rule(Pattern.compile(regex), lever, hint, null);
	}

	/** A rule scoped to one extended section's rows only (never code rows). */
	private static Rule sectionRule(String key, String regex, Lever lever, String hint) {
		return new Rule(Pattern.compile(regex), lever, hint, key);
	}

	/**
	 * The hedged fix directions for a summary — one per matched shape, each tagged
	 * {@code [structural]}/{@code [inherent]} and grounded in the row that triggered it,
	 * deduplicated, with structural (safe-to-pull) levers listed first.
	 * @param s the summary to scan
	 * @return the hint lines (possibly empty)
	 */
	public static List<String> hints(ProfileSummary s) {
		Map<String, Match> byHint = new LinkedHashMap<>();
		scan(rows(s), null, byHint);
		for (ProfileSummary.Section sec : s.sections()) {
			scan(sec.rows(), sec.key(), byHint);
		}
		List<String> out = new ArrayList<>();
		byHint.entrySet()
			.stream()
			.sorted(Comparator.comparing((e) -> e.getValue().lever()))
			.forEach((e) -> out.add(e.getValue().render(e.getKey())));
		return out;
	}

	/**
	 * Match {@code rows} against every rule whose {@code sectionKey} equals {@code key}
	 * ({@code null} = the code rows: hot paths / leaves / allocations / locks).
	 */
	private static void scan(List<Ranked> rows, String key, Map<String, Match> byHint) {
		for (Ranked r : rows) {
			String text = r.name() + " " + ((r.stack() != null) ? r.stack() : "");
			for (Rule rule : RULES) {
				if (Objects.equals(rule.sectionKey(), key) && rule.pattern().matcher(text).find()) {
					byHint.putIfAbsent(rule.hint(), new Match(rule.lever(), r.name()));
				}
			}
		}
	}

	/** The hints as a markdown section, or empty when nothing matched. */
	public static String render(ProfileSummary s) {
		List<String> hints = hints(s);
		if (hints.isEmpty()) {
			return "";
		}
		StringBuilder md = new StringBuilder(HEADER);
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

	/**
	 * A hint rule; {@code sectionKey} scopes it to one extended section ({@code null} =
	 * code rows).
	 */
	private record Rule(Pattern pattern, Lever lever, String hint, String sectionKey) {
	}

	/** A matched rule's lever and the row name that triggered it. */
	private record Match(Lever lever, String where) {

		/** The rendered hint line: {@code [lever] hint (`where`)}. */
		private String render(String hint) {
			return "[" + this.lever.tag + "] " + hint + " (`" + this.where + "`)";
		}

	}

}
