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
		String s = STRING_LITERAL.matcher(sql).replaceAll("?");
		s = NUMBER_LITERAL.matcher(s).replaceAll("?");
		s = PLACEHOLDER_LIST.matcher(s).replaceAll("(?)");
		s = WHITESPACE.matcher(s).replaceAll(" ").trim();
		if (s.length() > MAX_LEN) {
			s = s.substring(0, MAX_LEN) + "…";
		}
		return s.toLowerCase(Locale.ROOT);
	}

}
