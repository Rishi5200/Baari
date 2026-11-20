package com.baari.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.baari.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox relay, consumer dedup and the dead-letter topic against real Kafka (PLAN §12 Phase 5).
 * The relay runs on its normal schedule; tests insert outbox rows directly and watch the topic.
 */
@Import(OutboxRelayIT.SideEffectConsumer.class)
class OutboxRelayIT extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    SideEffectConsumer sideEffects;

    private UUID insertOutbox(String payload) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox_event (id, aggregate_type, aggregate_id, partition_key, event_type, payload, created_at)
                        VALUES (:id, 'QueueDay', :agg, CAST(:agg AS text), 'TestEvent', CAST(:payload AS jsonb), :at)
                        """)
                .param("id", id)
                .param("agg", UUID.randomUUID())
                .param("payload", payload)
                .param("at", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return id;
    }

    private boolean published(UUID id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** Reads a whole topic from its start and returns the event ids seen, in order. */
    private List<String> eventIdsOn(String topic) {
        List<String> ids = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("reader-" + UUID.randomUUID(), "")) {
            consumer.subscribe(List.of(topic));
            long stop = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < stop) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    ids.add(new String(
                            record.headers().lastHeader(EventHeaders.EVENT_ID).value(), StandardCharsets.UTF_8));
                }
            }
        }
        return ids;
    }

    @Test
    void aCommittedOutboxRowReachesTheTopicWithItsIdAndType() {
        UUID id = insertOutbox("{\"hello\":\"world\"}");

        await().atMost(Duration.ofSeconds(15)).until(() -> published(id));

        assertThat(eventIdsOn(EventTopics.QUEUE_EVENTS)).contains(id.toString());
    }

    @Test
    void rowsWrittenWhileKafkaIsDownAreDeliveredWhenItReturns() {
        List<UUID> ids = new ArrayList<>();
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            for (int i = 0; i < 5; i++) {
                ids.add(insertOutbox("{\"n\":" + i + "}"));
            }
            // The relay tries, fails and keeps the rows; nothing is lost and nothing is marked sent.
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(jdbc.sql("""
                                    SELECT count(*) FROM outbox_event WHERE id IN (:ids) AND attempts > 0
                                    """)
                                    .param("ids", ids)
                                    .query(Integer.class)
                                    .single())
                            .isPositive());
            assertThat(ids).noneMatch(this::published);
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> ids.stream().allMatch(this::published));

        List<String> onTopic = eventIdsOn(EventTopics.QUEUE_EVENTS);
        assertThat(onTopic).containsAll(ids.stream().map(UUID::toString).toList());
        // Whatever the topic holds, the consumer's side effect happened once per event.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> ids.forEach(id -> assertThat(sideEffects.count(id))
                        .as("side effects for %s", id)
                        .isEqualTo(1)));
    }

    @Test
    void theSameEventDeliveredTwiceHasItsSideEffectOnce() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(EventTopics.QUEUE_EVENTS, "dup-key", "{\"dup\":true}");
            record.headers().add(EventHeaders.EVENT_ID, id.toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, "TestEvent".getBytes(StandardCharsets.UTF_8));
            kafka.send(record).join();
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> sideEffects.count(id) >= 1);
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(sideEffects.count(id)).isEqualTo(1));
        assertThat(sideEffects.seen(id))
                .as("both deliveries reached the consumer")
                .isEqualTo(2);
    }

    @Test
    void aPoisonMessageIsParkedOnTheDeadLetterTopicAfterRetries() {
        UUID id = UUID.randomUUID();
        ProducerRecord<String, String> record =
                new ProducerRecord<>(EventTopics.QUEUE_EVENTS, "poison-key", "{\"poison\":true}");
        record.headers().add(EventHeaders.EVENT_ID, id.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, "TestEvent".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> eventIdsOn(EventTopics.QUEUE_EVENTS_DLT).contains(id.toString()));

        assertThat(sideEffects.seen(id)).as("tried the first time and retried").isGreaterThanOrEqualTo(3);
        assertThat(sideEffects.count(id)).isZero();
    }

    /** A stand-in consumer: claims the event, then "does" a side effect (a counter). Fails on poison. */
    @TestConfiguration
    static class SideEffectConsumer {

        private final Map<UUID, AtomicInteger> effects = new ConcurrentHashMap<>();
        private final Map<UUID, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        @Autowired
        ProcessedEvents processed;

        @Autowired
        TransactionTemplate tx;

        int count(UUID id) {
            return effects.getOrDefault(id, new AtomicInteger()).get();
        }

        int seen(UUID id) {
            return deliveries.getOrDefault(id, new AtomicInteger()).get();
        }

        @KafkaListener(topics = EventTopics.QUEUE_EVENTS, groupId = "relay-it")
        void on(@Payload String payload, @Header(EventHeaders.EVENT_ID) byte[] idBytes) {
            UUID id = UUID.fromString(new String(idBytes, StandardCharsets.UTF_8));
            deliveries.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            tx.executeWithoutResult(status -> {
                if (!processed.claim("relay-it", id)) {
                    return;
                }
                if (payload.contains("poison")) {
                    throw new IllegalStateException("cannot handle this one");
                }
                effects.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            });
        }
    }
}
