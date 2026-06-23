package org.alexmond.jvmlens;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {

	@Test
	void defaultScopeSkipsJdkAndCommonFrameworks() {
		Scope s = Scope.defaults();
		assertThat(s.isApplication("org.alexmond.jhelm.Render")).isTrue();
		assertThat(s.isApplication("java.util.HashMap")).isFalse();
		assertThat(s.isApplication("org.springframework.boot.loader.zip.ZipString")).isFalse();
		assertThat(s.isApplication("org.bouncycastle.math.ec.LongArray")).isFalse();
		assertThat(s.isApplication("com.fasterxml.jackson.databind.ObjectMapper")).isFalse();
	}

	@Test
	void includeModeKeepsOnlyListedPrefixes() {
		Scope s = Scope.of(List.of("org.alexmond.jhelm."), List.of());
		assertThat(s.isApplication("org.alexmond.jhelm.Render")).isTrue();
		// In include mode even non-framework code outside the list is not application.
		assertThat(s.isApplication("org.springframework.boot.Loader")).isFalse();
		assertThat(s.isApplication("com.example.Other")).isFalse();
	}

	@Test
	void excludeAddsToTheDefaultSkipList() {
		Scope s = Scope.of(List.of(), List.of("com.example.generated."));
		assertThat(s.isApplication("com.example.generated.Stub")).isFalse();
		assertThat(s.isApplication("com.example.app.Service")).isTrue();
	}

	@Test
	void ofIsNullSafe() {
		Scope s = Scope.of(null, null);
		assertThat(s.includePackages()).isEmpty();
		assertThat(s.excludePackages()).isEmpty();
		assertThat(s.isApplication("com.example.app.Service")).isTrue();
	}

}
