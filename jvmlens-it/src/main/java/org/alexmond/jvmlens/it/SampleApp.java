package org.alexmond.jvmlens.it;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A deliberately realistic host for the agent integration tests: Spring Boot web
 * (servlets → {@code web} capture), Spring Data JPA / Hibernate (entity enhancement +
 * DB-metadata queries → the {@code EntityManagerFactory} path that crashed in #68 Bug 2),
 * and a real JDBC driver ({@code db} capture). {@link Exerciser} drives one DB round-trip
 * and one HTTP request after startup, then exits 0 — so an IT can assert the context
 * booted cleanly under the agent (a crash leaves no {@code JVMLENS-IT-READY} marker and a
 * non-zero exit).
 */
@SpringBootApplication
public class SampleApp {

	public static void main(String[] args) {
		SpringApplication.run(SampleApp.class, args);
	}

}
