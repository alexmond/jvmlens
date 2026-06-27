package org.alexmond.jvmlens.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSanitizerTest {

	@Test
	void parameterizesStringAndNumberLiterals() {
		String shape = SqlSanitizer.sanitize("SELECT * FROM orders WHERE id = 42 AND name = 'alice'");
		assertThat(shape).isEqualTo("select * from orders where id = ? and name = ?");
	}

	@Test
	void collapsesInListsAndWhitespace() {
		String shape = SqlSanitizer.sanitize("SELECT *\n  FROM t\tWHERE id IN (1, 2, 3, 4)");
		assertThat(shape).isEqualTo("select * from t where id in (?)");
	}

	@Test
	void differentLiteralsProduceTheSameShape() {
		assertThat(SqlSanitizer.sanitize("UPDATE u SET x = 1 WHERE id = 7"))
			.isEqualTo(SqlSanitizer.sanitize("UPDATE u SET x = 999 WHERE id = 3"));
	}

	@Test
	void blankOrNullBecomesQuestionMark() {
		assertThat(SqlSanitizer.sanitize(null)).isEqualTo("?");
		assertThat(SqlSanitizer.sanitize("   ")).isEqualTo("?");
	}

	@Test
	void truncatesVeryLongStatements() {
		String shape = SqlSanitizer.sanitize("SELECT " + "a, ".repeat(200) + "b FROM t");
		assertThat(shape).hasSizeLessThanOrEqualTo(201).endsWith("…");
	}

	@Test
	void hugeQuotedLiteralDoesNotOverflowTheStack() {
		// #68 Bug 2: '(?:[^']|'')*' recurses one stack frame per char, so a long quoted
		// literal (a big IN-list or driver metadata SQL) blew the stack at the call site
		// — which during Hibernate EntityManagerFactory build crashed the host. Bounding
		// the scanned input keeps sanitize() safe regardless of statement size.
		String huge = "SELECT * FROM t WHERE k = '" + "x".repeat(200_000) + "'";
		String shape = SqlSanitizer.sanitize(huge); // must not StackOverflowError
		assertThat(shape).hasSizeLessThanOrEqualTo(201);
	}

}
