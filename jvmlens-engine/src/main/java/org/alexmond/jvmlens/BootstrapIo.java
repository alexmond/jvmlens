package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

/**
 * File I/O the JVM does for itself rather than for the workload: reading class bytes off
 * the class path, and a JDK class's static initializer reading the JDK's own config
 * ({@code conf/net.properties}, {@code java.security}, …). Such a row names the jar the
 * run happened to start from, so an A/B of two differently named jars showed
 * {@code main.jar — GONE} and {@code pr.jar — NEW} on a pure-CPU benchmark (#192).
 * Dropped silently, like the recorder's own {@code file null} sink.
 * <p>
 * The test is the event's origin, never its size: an application reading its own file, or
 * a resource out of a jar, is application I/O and stays.
 */
final class BootstrapIo {

	/**
	 * JDK class-loader types; a class-loading method on one of them means class loading.
	 */
	private static final List<String> LOADER_TYPES = List.of("java.lang.ClassLoader", "java.security.SecureClassLoader",
			"java.net.URLClassLoader", "jdk.internal.loader.");

	private static final Set<String> LOADER_METHODS = Set.of("loadClass", "loadClassOrNull", "findClass",
			"findClassOnClassPathOrNull", "defineClass");

	/** The JDK proper — whose static initializers read only the JDK's own files. */
	private static final List<String> JDK_TYPES = List.of("java.", "jdk.", "sun.");

	private BootstrapIo() {
	}

	/**
	 * Whether a file I/O event was the JVM bootstrapping rather than the workload.
	 * @param event a {@code jdk.FileRead} / {@code jdk.FileWrite} event
	 * @return {@code true} for class loading or a JDK static initializer's read
	 */
	static boolean isBootstrap(RecordedEvent event) {
		RecordedStackTrace stack = event.getStackTrace();
		if (stack == null) {
			return false;
		}
		List<Frame> frames = new ArrayList<>();
		for (RecordedFrame frame : stack.getFrames()) {
			if (frame.isJavaFrame()) {
				frames.add(new Frame(frame.getMethod().getType().getName(), frame.getMethod().getName()));
			}
		}
		return isBootstrap(frames);
	}

	/**
	 * The stack rule, leaf first. Class loading anywhere on the stack counts (a custom
	 * loader such as Spring Boot's still reads through {@code ClassLoader.loadClass}). A
	 * JDK {@code <clinit>} counts only when every frame above it is JDK too — an
	 * application callback in between (a service provider the JDK initializes) reads for
	 * the application.
	 * @param leafFirst the stack's Java frames, leaf first
	 * @return {@code true} for class loading or a JDK static initializer's read
	 */
	static boolean isBootstrap(List<Frame> leafFirst) {
		boolean jdkSoFar = true;
		for (Frame frame : leafFirst) {
			if (isClassLoading(frame)) {
				return true;
			}
			jdkSoFar = jdkSoFar && isJdk(frame.type());
			if (jdkSoFar && "<clinit>".equals(frame.method())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isClassLoading(Frame frame) {
		if (!LOADER_METHODS.contains(frame.method())) {
			return false;
		}
		for (String loader : LOADER_TYPES) {
			if (frame.type().startsWith(loader)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isJdk(String type) {
		for (String prefix : JDK_TYPES) {
			if (type.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * One Java frame.
	 *
	 * @param type the declaring type's name
	 * @param method the method name
	 */
	record Frame(String type, String method) {
	}

}
