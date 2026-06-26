package org.alexmond.jvmlens.probe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.cache.CacheStore;
import org.alexmond.jvmlens.messaging.MessagingStore;

import static org.assertj.core.api.Assertions.assertThat;

class StoreFacadesTest {

	@AfterEach
	void clear() {
		MessagingStore.reset();
		CacheStore.reset();
	}

	@Test
	void messagingFacadeRecordsUnderTheMessagingKey() {
		MessagingStore.record("com.acme.KafkaProducer.send", 5_000_000L);
		assertThat(MessagingStore.sections()).singleElement()
			.satisfies((s) -> assertThat(s.key()).isEqualTo("messaging"));
		assertThat(MessagingStore.sections().get(0).title()).contains("Messaging operations");
	}

	@Test
	void cacheFacadeRecordsUnderTheCacheKey() {
		CacheStore.record("com.acme.RedisCache.get", 2_000_000L);
		assertThat(CacheStore.sections()).singleElement().satisfies((s) -> assertThat(s.key()).isEqualTo("cache"));
		assertThat(CacheStore.sections().get(0).rows().get(0).name()).isEqualTo("RedisCache.get");
	}

}
