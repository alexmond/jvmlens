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
		assertThat(s.isApplication("org.thymeleaf.engine.AbstractTemplateEvent")).isFalse();
		assertThat(s.isApplication("org.hibernate.loader.Loader")).isFalse();
		assertThat(s.isApplication("groovy.lang.MetaClassImpl")).isFalse();
	}

	@Test
	void defaultScopeNeverTreatsTestLibrariesAsApplicationCode() {
		// a Mockito run used to report `org.mockito.internal…` as the application hot
		// path
		Scope s = Scope.defaults();
		assertThat(s.isApplication("org.mockito.internal.handler.MockHandlerImpl")).isFalse();
		assertThat(s.isApplication("org.junit.jupiter.engine.execution.InvocationInterceptorChain")).isFalse();
		assertThat(s.isApplication("net.bytebuddy.implementation.bind.MethodDelegationBinder")).isFalse();
		assertThat(s.isApplication("org.assertj.core.api.AbstractAssert")).isFalse();
		assertThat(s.isApplication("org.openjdk.jmh.runner.BenchmarkHandler")).isFalse();
		assertThat(s.isApplication("org.testcontainers.containers.GenericContainer")).isFalse();
		// jvmlens's own bench driver is the harness, not the workload
		assertThat(s.isApplication("org.alexmond.jvmlens.BenchCommand")).isFalse();
		// …but the rest of that package still is (jvmlens profiles itself in its tests)
		assertThat(s.isApplication("org.alexmond.jvmlens.testimpl.BenchWorkload")).isTrue();
	}

	@Test
	void anExplicitIncludeStillWinsOverTheTestLibraryDefault() {
		// someone profiling Mockito itself asks for it by name
		Scope s = Scope.of(List.of("org.mockito."), List.of());
		assertThat(s.isApplication("org.mockito.internal.handler.MockHandlerImpl")).isTrue();
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
	void excludeWinsEvenInsideAnIncludePackage() {
		// #121: -x carves a test/generated class out of an -a roll-up that shares the
		// root
		Scope s = Scope.of(List.of("org.alexmond.jhelm"), List.of("org.alexmond.jhelm.core.KpsComparisonTest"));
		assertThat(s.isApplication("org.alexmond.jhelm.Render")).isTrue();
		assertThat(s.isApplication("org.alexmond.jhelm.core.KpsComparisonTest")).isFalse();
	}

	@Test
	void treatsNativeFramesAsNonApplication() {
		Scope s = Scope.defaults();
		assertThat(s.isApplication("libjvm.so")).isFalse();
		assertThat(s.isApplication("libjvm.so.PhaseIdealLoop")).isFalse();
		assertThat(s.isApplication("G1CollectedHeap::humongous_obj_allocate")).isFalse();
		assertThat(s.isApplication("org.alexmond.App")).isTrue();
	}

	@Test
	void ofIsNullSafe() {
		Scope s = Scope.of(null, null);
		assertThat(s.includePackages()).isEmpty();
		assertThat(s.excludePackages()).isEmpty();
		assertThat(s.isApplication("com.example.app.Service")).isTrue();
	}

}
