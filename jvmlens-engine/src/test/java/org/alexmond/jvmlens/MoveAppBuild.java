package org.alexmond.jvmlens;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.alexmond.jvmlens.moveapp.Caller;

/**
 * One build of the {@code moveapp} test application: a fresh copy of the package in its
 * own class loader, its variant fixed when {@link Caller} initialises. Each side of a
 * move test runs its own build, as two releases of an app would, so the two sides never
 * share a method profile or compiled code (#211).
 */
final class MoveAppBuild extends ClassLoader {

	private static final String PACKAGE = "org.alexmond.jvmlens.moveapp.";

	private MoveAppBuild(ClassLoader parent) {
		super(parent);
	}

	/**
	 * Loads a build and returns one unit of its work.
	 * @param mode the {@link Caller} variant this build is
	 * @param map the map the entry points look keys up in
	 * @param rounds the rounds each entry point runs per unit
	 * @param entries the entry points, as {@code Class.method} in {@code moveapp}, each
	 * taking {@code (Map, int)}
	 * @return one unit of work: every entry point run once, results summed
	 */
	static LongSupplier load(int mode, Map<Integer, Integer> map, int rounds, String... entries)
			throws ReflectiveOperationException {
		MoveAppBuild loader = new MoveAppBuild(MoveAppBuild.class.getClassLoader());
		String previous = System.setProperty(Caller.MODE_PROPERTY, Integer.toString(mode));
		List<Method> methods = new ArrayList<>();
		try {
			for (String entry : entries) {
				int dot = entry.indexOf('.');
				Class<?> type = Class.forName(PACKAGE + entry.substring(0, dot), true, loader);
				methods.add(type.getMethod(entry.substring(dot + 1), Map.class, int.class));
			}
		}
		finally {
			if (previous == null) {
				System.clearProperty(Caller.MODE_PROPERTY);
			}
			else {
				System.setProperty(Caller.MODE_PROPERTY, previous);
			}
		}
		return () -> {
			long sum = 0;
			for (Method m : methods) {
				try {
					sum += (Long) m.invoke(null, map, rounds);
				}
				catch (ReflectiveOperationException ex) {
					throw new IllegalStateException(ex);
				}
			}
			return sum;
		};
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (!name.startsWith(PACKAGE)) {
			return super.loadClass(name, resolve);
		}
		Class<?> type = findLoadedClass(name);
		if (type == null) {
			type = define(name);
		}
		if (resolve) {
			resolveClass(type);
		}
		return type;
	}

	private Class<?> define(String name) throws ClassNotFoundException {
		try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
			if (in == null) {
				throw new ClassNotFoundException(name);
			}
			byte[] bytes = in.readAllBytes();
			return defineClass(name, bytes, 0, bytes.length);
		}
		catch (IOException ex) {
			throw new ClassNotFoundException(name, ex);
		}
	}

}
