package org.alexmond.jvmlens.probe;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

import static org.assertj.core.api.Assertions.assertThat;

class OpStoreTest {

	@Test
	void emptyStoreHasNoSection() {
		assertThat(new OpStore().sections("messaging", "Messaging")).isEmpty();
	}

	@Test
	void shortensFullyQualifiedLabelsToTypeDotMethod() {
		assertThat(OpStore.shorten("org.apache.kafka.clients.producer.KafkaProducer.send"))
			.isEqualTo("KafkaProducer.send");
		assertThat(OpStore.shorten("send")).isEqualTo("send");
		assertThat(OpStore.shorten(null)).isEqualTo("?");
	}

	@Test
	void aggregatesByLabelAndRanksByTotalTime() {
		OpStore store = new OpStore();
		store.record("com.acme.KafkaProducer.send", 30_000_000L);
		store.record("com.acme.KafkaProducer.send", 30_000_000L);
		store.record("com.acme.KafkaConsumer.poll", 10_000_000L);
		List<Section> sections = store.sections("messaging", "Messaging operations (by total time)");
		assertThat(sections).hasSize(1);
		Section s = sections.get(0);
		assertThat(s.key()).isEqualTo("messaging");
		assertThat(s.measured()).isTrue();
		List<Ranked> rows = s.rows();
		assertThat(rows.get(0).name()).isEqualTo("KafkaProducer.send");
		assertThat(rows.get(0).stack()).contains("2 ops").contains("avg 30.0 ms");
		assertThat(rows).anyMatch((r) -> r.name().equals("KafkaConsumer.poll"));
	}

	@Test
	void resetClearsState() {
		OpStore store = new OpStore();
		store.record("A.b", 1_000_000L);
		assertThat(store.sections("cache", "Cache")).isNotEmpty();
		store.reset();
		assertThat(store.sections("cache", "Cache")).isEmpty();
	}

}
