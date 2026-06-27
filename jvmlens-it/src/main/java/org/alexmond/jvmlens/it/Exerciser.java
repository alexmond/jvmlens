package org.alexmond.jvmlens.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** After boot: one DB write + read and one self HTTP call, then exit 0. */
@Component
public class Exerciser {

	private final WidgetService service;

	private final ApplicationContext ctx;

	// Set on WebServerInitializedEvent, read on the later ApplicationReadyEvent — both
	// fire
	// sequentially on the boot thread, so no cross-thread visibility concern.
	private int port;

	public Exerciser(WidgetService service, ApplicationContext ctx) {
		this.service = service;
		this.ctx = ctx;
	}

	@EventListener
	public void onWebServer(WebServerInitializedEvent event) {
		this.port = event.getWebServer().getPort();
	}

	@EventListener
	public void onReady(ApplicationReadyEvent event) throws Exception {
		long count = this.service.persistAndCount("it"); // db: INSERT+SELECT via
															// @Transactional proxy
		this.service.fireHostileSql(); // db: SqlSanitizer stress (long literal + IN-list,
										// #79)
		HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/widgets")).build(),
					HttpResponse.BodyHandlers.ofString()); // web: HTTP → servlet
		Thread.sleep(3000); // let the agent tick at least once and write its summary
		System.out.println("JVMLENS-IT-READY widgets=" + count);
		System.exit(SpringApplication.exit(this.ctx, () -> 0));
	}

}
