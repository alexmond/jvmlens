package org.alexmond.jvmlens;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapIoTest {

	private static final String PROBE = BootstrapIoTest.class.getName() + "$Probe";

	@TempDir
	Path dir;

	@Test
	void classLoadingReadsOfTheClasspathJarAreNotApplicationIo() throws Exception {
		// #192: a pure-CPU JMH A/B showed `file …/main.jar — GONE` and `file …/pr.jar —
		// NEW` because each side loaded its classes from a differently named jar.
		Path classes = jar("main-classes.jar", PROBE.replace('.', '/') + ".class", probeBytes());
		Path resources = jar("app-resources.jar", "data.txt", new byte[64 * 1024]);
		Path appData = Files.write(this.dir.resolve("app-data.bin"), new byte[64 * 1024]);
		Path jfr = this.dir.resolve("bootstrap-io.jfr");
		try (Recording recording = new Recording()) {
			recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
			recording.start();
			try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, null)) {
				assertThat(loader.loadClass(PROBE).getClassLoader()).isSameAs(loader);
			}
			// look-alikes that must stay: the app reading its own file, and the app
			// reading a resource out of a jar (not class loading)
			assertThat(Files.readAllBytes(appData)).hasSize(64 * 1024);
			try (URLClassLoader loader = new URLClassLoader(new URL[] { resources.toUri().toURL() }, null);
					InputStream in = loader.getResourceAsStream("data.txt")) {
				assertThat(in.readAllBytes()).hasSize(64 * 1024);
			}
			recording.stop();
			recording.dump(jfr);
		}
		List<String> rows = ioRows(Summarizer.analyze(jfr));
		Assumptions.assumeTrue(rows.stream().anyMatch((r) -> r.contains("app-data.bin")),
				"JFR file-read events not captured on this platform");
		assertThat(rows).noneMatch((r) -> r.contains("main-classes.jar"));
		assertThat(rows).anyMatch((r) -> r.contains("app-resources.jar"));
	}

	@Test
	void aJdkStaticInitializerReadingItsConfigIsBootstrap() {
		// #192: `…/conf/net.properties — NEW 1 ms` — the stack as measured on JDK 25
		assertThat(BootstrapIo.isBootstrap(List.of(f("java.util.Properties$LineReader", "readLine"),
				f("java.util.Properties", "load0"), f("java.util.Properties", "load"),
				f("sun.net.NetProperties", "loadDefaultProperties"), f("sun.net.NetProperties", "<clinit>"),
				f("sun.net.www.protocol.http.HttpURLConnection", "<clinit>"), f("java.net.URL", "openConnection"),
				f("com.acme.Client", "fetch"))))
			.isTrue();
	}

	@Test
	void anApplicationReadUnderAJdkInitializerStaysApplicationIo() {
		// look-alikes: the app's own static initializer, and an app callback (a
		// service provider) that a JDK initializer runs
		assertThat(BootstrapIo.isBootstrap(List.of(f("java.nio.file.Files", "readAllBytes"),
				f("com.acme.Config", "<clinit>"), f("com.acme.Main", "main"))))
			.isFalse();
		assertThat(BootstrapIo.isBootstrap(List.of(f("java.io.FileInputStream", "read"),
				f("com.acme.spi.Provider", "load"), f("java.util.ServiceLoader$1", "next"),
				f("java.nio.charset.Charset", "<clinit>"), f("com.acme.Main", "main"))))
			.isFalse();
		assertThat(BootstrapIo.isBootstrap(List.of(f("java.io.FileInputStream", "read"), f("com.acme.Main", "main"))))
			.isFalse();
	}

	@Test
	void classLoadingThroughACustomLoaderIsBootstrap() {
		// Spring Boot's launcher reads nested jars with its own code, under
		// ClassLoader.loadClass
		assertThat(BootstrapIo.isBootstrap(List.of(f("org.springframework.boot.loader.zip.FileDataBlock", "read"),
				f("java.net.URLClassLoader", "defineClass"), f("java.net.URLClassLoader", "findClass"),
				f("org.springframework.boot.loader.net.protocol.jar.JarUrlClassLoader", "loadClass"),
				f("java.lang.ClassLoader", "loadClass"), f("com.acme.Main", "main"))))
			.isTrue();
		// a method that is merely named like class loading, on a non-loader type
		assertThat(BootstrapIo.isBootstrap(List.of(f("java.io.FileInputStream", "read"),
				f("com.acme.Registry", "loadClass"), f("com.acme.Main", "main"))))
			.isFalse();
	}

	private static BootstrapIo.Frame f(String type, String method) {
		return new BootstrapIo.Frame(type, method);
	}

	private static List<String> ioRows(ProfileSummary s) {
		return s.sections()
			.stream()
			.filter((sec) -> "io".equals(sec.key()))
			.flatMap((sec) -> sec.rows().stream())
			.map(ProfileSummary.Ranked::name)
			.toList();
	}

	private Path jar(String name, String entry, byte[] content) throws Exception {
		Path jar = this.dir.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
			jos.putNextEntry(new JarEntry(entry));
			jos.write(content);
			jos.closeEntry();
		}
		return jar;
	}

	private static byte[] probeBytes() throws Exception {
		try (InputStream in = BootstrapIoTest.class.getResourceAsStream("BootstrapIoTest$Probe.class")) {
			return in.readAllBytes();
		}
	}

	/** Loaded from a jar by a fresh loader — its bytes are the class-loading read. */
	static final class Probe {

	}

}
