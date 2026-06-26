package org.alexmond.jvmlens;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@SpringBootApplication
public class Main {

	private int exitCode;

	public static void main(String[] args) {
		System.exit(SpringApplication.exit(SpringApplication.run(Main.class, args)));
	}

	@Bean
	CommandLineRunner runner(IFactory factory, JvmlensCommand root) {
		return (args) -> dispatch(factory, root, args);
	}

	@Bean
	ExitCodeGenerator exitCodeGenerator() {
		return () -> this.exitCode;
	}

	private void dispatch(IFactory factory, JvmlensCommand root, String[] args) {
		this.exitCode = new CommandLine(root, factory).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
	}

}
