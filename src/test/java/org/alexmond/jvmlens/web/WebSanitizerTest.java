package org.alexmond.jvmlens.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSanitizerTest {

	@Test
	void replacesNumericIdSegments() {
		assertThat(WebSanitizer.route("/orders/123/items/456")).isEqualTo("/orders/{}/items/{}");
	}

	@Test
	void replacesUuidAndLongTokenSegments() {
		assertThat(WebSanitizer.route("/users/550e8400-e29b-41d4-a716-446655440000")).isEqualTo("/users/{}");
		assertThat(WebSanitizer.route("/blob/0123456789abcdef0123")).isEqualTo("/blob/{}");
	}

	@Test
	void dropsQueryStringAndKeepsStaticSegments() {
		assertThat(WebSanitizer.route("/search?q=secret&page=2")).isEqualTo("/search");
		assertThat(WebSanitizer.route("/api/v1/health")).isEqualTo("/api/v1/health");
	}

	@Test
	void blankBecomesRoot() {
		assertThat(WebSanitizer.route(null)).isEqualTo("/");
		assertThat(WebSanitizer.route("")).isEqualTo("/");
		assertThat(WebSanitizer.route("/")).isEqualTo("/");
	}

}
