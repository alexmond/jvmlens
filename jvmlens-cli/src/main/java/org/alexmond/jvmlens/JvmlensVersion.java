package org.alexmond.jvmlens;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import picocli.CommandLine.IVersionProvider;

/**
 * The one source of the jvmlens version: {@code build.version} from the
 * {@code META-INF/build-info.properties} that the spring-boot-maven-plugin
 * {@code build-info} goal writes at build time. Read statically, so it needs no Spring
 * context; backs both {@code --version} and the MCP server's {@code serverInfo} (#192).
 */
public class JvmlensVersion implements IVersionProvider {

	static final String RESOURCE = "/META-INF/build-info.properties";

	/** What a run outside a Maven build (no build-info on the class path) reports. */
	static final String UNKNOWN = "unknown";

	private static final String VERSION = read(JvmlensVersion.class.getResourceAsStream(RESOURCE));

	/**
	 * The build version.
	 * @return e.g. {@code 0.3.2}, or {@value #UNKNOWN} without build info
	 */
	public static String get() {
		return VERSION;
	}

	@Override
	public String[] getVersion() {
		return new String[] { "jvmlens " + VERSION };
	}

	static String read(InputStream in) {
		if (in == null) {
			return UNKNOWN;
		}
		try (InputStream stream = in) {
			Properties props = new Properties();
			props.load(stream);
			String version = props.getProperty("build.version");
			return (version == null || version.isBlank()) ? UNKNOWN : version.strip();
		}
		catch (IOException ex) {
			return UNKNOWN;
		}
	}

}
