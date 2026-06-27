package org.alexmond.jvmlens.it;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Widens the agent's host matrix (#79). Two extra shapes the bare repository couldn't
 * exercise:
 *
 * <ul>
 * <li><b>A Spring-proxied bean</b> — {@code @Transactional} makes calls route through a
 * Spring AOP proxy (and the {@code @Configuration} class is CGLIB-enhanced), so the
 * agent's {@code db} matchers transform/skip synthetic + generated types under load, not
 * just the plain entity graph that boots in the minimal host.</li>
 * <li><b>A raw {@link JdbcTemplate} path</b> (no Hibernate) firing SQL whose
 * <em>text</em> carries the {@code SqlSanitizer}-SOE shapes from #68 Bug 2 — a very long
 * single-quoted literal and a long {@code IN (...)} list. The {@code db} advice must
 * sanitize these without crashing the host (fail-open, #74) and keep capturing.</li>
 * </ul>
 */
@Service
public class WidgetService {

	private static final int HOSTILE_LITERAL_LEN = 20_000;

	private static final int HOSTILE_IN_LIST_LEN = 2_000;

	private final WidgetRepository repo;

	private final JdbcTemplate jdbc;

	public WidgetService(WidgetRepository repo, JdbcTemplate jdbc) {
		this.repo = repo;
		this.jdbc = jdbc;
	}

	/**
	 * Transactional → the call routes through a Spring proxy (the {@code db} proxy path).
	 */
	@Transactional
	public long persistAndCount(String name) {
		this.repo.save(new Widget(name));
		return this.repo.count();
	}

	/**
	 * Raw-JDBC SQL whose text carries the {@code SqlSanitizer}-SOE shapes (a long quoted
	 * literal and a long {@code IN} list) — the #68 Bug 2 trigger, on a non-Hibernate
	 * path.
	 */
	public void fireHostileSql() {
		String hugeLiteral = "y".repeat(HOSTILE_LITERAL_LEN);
		this.jdbc.queryForObject("SELECT count(*) FROM widget WHERE name = '" + hugeLiteral + "'", Long.class);
		String inList = IntStream.range(0, HOSTILE_IN_LIST_LEN)
			.mapToObj((i) -> "'v" + i + "'")
			.collect(Collectors.joining(","));
		this.jdbc.queryForObject("SELECT count(*) FROM widget WHERE name IN (" + inList + ")", Long.class);
	}

}
