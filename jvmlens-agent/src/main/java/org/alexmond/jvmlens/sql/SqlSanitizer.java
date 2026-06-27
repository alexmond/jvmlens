package org.alexmond.jvmlens.sql;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduces a concrete SQL statement to its <em>shape</em> — literals parameterized,
 * whitespace collapsed — so executions of the same query aggregate together and no
 * literal values (potential PII) reach the summary. Mirrors what OTel / Glowroot do
 * before a query is recorded.
 */
public final class SqlSanitizer {

	/** Longest shape kept; longer statements are truncated with an ellipsis. */
	private static final int MAX_LEN = 200;

	/**
	 * Hard cap on how much raw SQL the regexes scan. Java's {@code java.util.regex}
	 * matches a greedy group like {@code '(?:[^']|'')*'} <em>recursively</em> — one stack
	 * frame per character of a quoted literal — so an unbounded statement (a huge
	 * {@code IN}-list, or a driver metadata query such as Postgres
	 * {@code getSQLKeywords}) overflows the stack at the call site, which during
	 * {@code EntityManagerFactory} build crashes the host (field-finding #68 Bug 2). The
	 * shape is truncated to {@link #MAX_LEN} anyway, so there is nothing to lose by not
	 * scanning past a safe bound.
	 */
	private static final int MAX_SCAN = 512;

	private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

	private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

	private static final Pattern PLACEHOLDER_LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private SqlSanitizer() {
	}

	/**
	 * Parameterize and normalize a statement into a stable shape.
	 * @param sql the raw SQL (may be {@code null})
	 * @return the sanitized shape, or {@code "?"} for a blank/unknown statement
	 */
	public static String sanitize(String sql) {
		if (sql == null || sql.isBlank()) {
			return "?";
		}
		// Bound the input first: the regexes below recurse per character, so a very long
		// literal would overflow the stack (#68 Bug 2). The output is capped anyway.
		String scoped = (sql.length() > MAX_SCAN) ? sql.substring(0, MAX_SCAN) : sql;
		String s = STRING_LITERAL.matcher(scoped).replaceAll("?");
		s = NUMBER_LITERAL.matcher(s).replaceAll("?");
		s = PLACEHOLDER_LIST.matcher(s).replaceAll("(?)");
		s = WHITESPACE.matcher(s).replaceAll(" ").trim();
		if (s.length() > MAX_LEN) {
			s = s.substring(0, MAX_LEN) + "…";
		}
		return s.toLowerCase(Locale.ROOT);
	}

}
