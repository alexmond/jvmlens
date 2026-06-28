package org.alexmond.jvmlens;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.alexmond.jvmlens.ProfileSummary.Ranked;

import static org.assertj.core.api.Assertions.assertThat;

class SourceResolverTest {

	@Test
	void echoesSourceLineAtAllocSiteAndHotLeaf(@TempDir Path tmp) throws Exception {
		Path pkg = Files.createDirectories(tmp.resolve("com/x"));
		Files.writeString(pkg.resolve("Foo.java"), "package com.x;\nclass Foo {\n    int bar() { return 42; }\n}\n");
		ProfileSummary s = new ProfileSummary("r.jfr", 100, 1, 0, 0, 0, List.of(),
				List.of(new Ranked("com.x.Foo.bar", 1.0, 5, "line 3")), // hot leaf
				List.of(new Ranked("com.x.Foo.bar", 1.0, 1000, ":3 · byte[] 1 KB")), // alloc
																						// site
				List.of(), List.of(), List.of(), "cause", "com.x");

		ProfileSummary d = SourceResolver.decorate(s, List.of(tmp));
		assertThat(d.hotLeaves().get(0).stack()).contains("⟶ int bar() { return 42; }");
		assertThat(d.allocSites().get(0).stack()).contains("⟶ int bar() { return 42; }");
	}

	@Test
	void unresolvableAnchorsAndNoRootsLeaveTheSummaryUntouched() {
		ProfileSummary s = new ProfileSummary("r.jfr", 100, 1, 0, 0, 0, List.of(),
				List.of(new Ranked("com.x.Missing.bar", 1.0, 5, "line 3")), List.of(), List.of(), List.of(), List.of(),
				"c", "com.x");
		assertThat(SourceResolver.decorate(s, List.of())).isSameAs(s); // no roots → no-op
		assertThat(SourceResolver.decorate(s, List.of(Path.of("/no/such/root")))).isSameAs(s); // file
																								// not
																								// found
		assertThat(SourceResolver.roots(null)).isEmpty();
		assertThat(SourceResolver.roots("a,b")).hasSize(2);
	}

}
