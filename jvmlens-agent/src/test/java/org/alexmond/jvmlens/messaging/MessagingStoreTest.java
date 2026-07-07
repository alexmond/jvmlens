package org.alexmond.jvmlens.messaging;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.probe.CallSites;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingStoreTest {

	@BeforeEach
	@AfterEach
	void clear() {
		MessagingStore.reset();
	}

	@Test
	void capturesTheCallerAndFlagsSynchronousSend() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 60; i++) {
			com.example.demo.OrderPublisher.send(3_000_000L); // 3ms each, 60 sends
		}
		Ranked row = MessagingStore.sections().get(0).rows().get(0);
		assertThat(row.stack()).contains("· at com.example.demo.OrderPublisher:")
			.contains("synchronous per-message send");
	}

	@Test
	void fastAsyncSendIsNotFlagged() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 200; i++) {
			com.example.demo.OrderPublisher.send(50_000L); // 0.05ms — well under the gate
		}
		assertThat(MessagingStore.sections().get(0).rows().get(0).stack())
			.doesNotContain("synchronous per-message send");
	}

	@Test
	void consumerPollIsNotFlagged() {
		CallSites.setAppScope(List.of("com.example"));
		for (int i = 0; i < 60; i++) {
			com.example.demo.OrderPublisher.poll(5_000_000L); // slow, but a poll, not a
																// send
		}
		assertThat(MessagingStore.sections().get(0).rows().get(0).stack())
			.doesNotContain("synchronous per-message send");
	}

}
