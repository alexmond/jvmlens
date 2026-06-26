package org.alexmond.jvmlens.web;

import java.util.regex.Pattern;

/**
 * Reduces a concrete request path to a low-cardinality <em>route shape</em> by replacing
 * variable segments (numeric ids, UUIDs, long hex/opaque tokens) with {@code {}}, so all
 * hits on the same route aggregate together instead of fragmenting per id. Query strings
 * are dropped (they carry values, often PII).
 */
public final class WebSanitizer {

	private static final int MAX_LEN = 120;

	private static final Pattern NUMERIC = Pattern.compile("\\d+");

	private static final Pattern UUID = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private static final Pattern LONG_TOKEN = Pattern.compile("[0-9a-fA-F]{16,}");

	private WebSanitizer() {
	}

	/**
	 * Normalize a request URI path into a route shape.
	 * @param path the request path (may include a query string; may be {@code null})
	 * @return the route shape, or {@code "/"} when blank
	 */
	public static String route(String path) {
		if (path == null || path.isBlank()) {
			return "/";
		}
		int q = path.indexOf('?');
		String p = (q >= 0) ? path.substring(0, q) : path;
		String[] segments = p.split("/");
		StringBuilder out = new StringBuilder();
		for (String seg : segments) {
			if (seg.isEmpty()) {
				continue;
			}
			out.append('/').append(isVariable(seg) ? "{}" : seg);
		}
		String shape = out.isEmpty() ? "/" : out.toString();
		return (shape.length() > MAX_LEN) ? (shape.substring(0, MAX_LEN) + "…") : shape;
	}

	private static boolean isVariable(String seg) {
		return NUMERIC.matcher(seg).matches() || UUID.matcher(seg).matches() || LONG_TOKEN.matcher(seg).matches();
	}

}
