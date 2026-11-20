package com.baari.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies committed outbox rows to Kafka (PLAN §11.2, ADR 0011). The row was written in the same
 * transaction as the queue change, so nothing is lost if Kafka is down; the relay just catches up.
 *
 * <p>Safe on several instances: a batch is claimed with {@code FOR UPDATE SKIP LOCKED}, so two
 * relays never send the same row at once. Delivery is still at-least-once (a crash after the send
 * but before the commit sends the row again), which is why consumers dedupe on the event id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    OutboxRelay(
            JdbcClient jdbc,
            TransactionTemplate tx,
            KafkaTemplate<String, String> kafka,
            Clock clock,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.clock = clock;
        Gauge.builder("baari.outbox.lag.seconds", this, OutboxRelay::lagSeconds)
                .description("Age of the oldest outbox row not yet sent to Kafka")
                .register(meters);
    }

    private record Row(UUID id, String key, String type, String payload) {}

    @Scheduled(fixedDelayString = "${baari.events.relay-delay-ms:500}")
    void relay() {
        try {
            while (relayBatch() == BATCH) {
                // more waiting: keep going without sleeping
            }
        } catch (RuntimeException e) {
            // Kafka or the database is unavailable; the rows stay unpublished and the next run retries.
            log.warn("Outbox relay run failed: {}", e.toString());
        }
    }

    /** Sends one batch. Returns how many rows were claimed. */
    int relayBatch() {
        Integer claimed = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                            SELECT id, partition_key, event_type, CAST(payload AS text) AS payload
                              FROM outbox_event
                             WHERE published_at IS NULL
                             ORDER BY created_at, id
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", BATCH)
                    .query((rs, n) -> new Row(
                            rs.getObject("id", UUID.class),
                            rs.getString("partition_key"),
                            rs.getString("event_type"),
                            rs.getString("payload")))
                    .list();
            for (Row row : rows) {
                if (!send(row)) {
                    // Keep order within the batch: stop at the first failure, retry from here next time.
                    break;
                }
                jdbc.sql("UPDATE outbox_event SET published_at = :now WHERE id = :id")
                        .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .param("id", row.id())
                        .update();
            }
            return rows.size();
        });
        return claimed == null ? 0 : claimed;
    }

    private boolean send(Row row) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(EventTopics.QUEUE_EVENTS, row.key(), row.payload());
        record.headers()
                .add(EventHeaders.EVENT_ID, row.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, row.type().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(row.id(), e);
            return false;
        } catch (Exception e) {
            recordFailure(row.id(), e);
            return false;
        }
    }

    private void recordFailure(UUID id, Exception e) {
        log.warn("Outbox row {} not sent: {}", id, e.toString());
        // Own statement inside the claim transaction: the attempt count survives because the
        // transaction still commits (only the published rows and this counter change).
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", String.valueOf(e.getMessage()))
                .param("id", id)
                .update();
    }

    /** Deletes rows sent more than a week ago (PLAN §11.2). */
    @Scheduled(cron = "${baari.events.cleanup-cron:0 17 3 * * *}")
    void cleanup() {
        int deleted = jdbc.sql("DELETE FROM outbox_event WHERE published_at < :cutoff")
                .param("cutoff", OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(7)), ZoneOffset.UTC))
                .update();
        if (deleted > 0) {
            log.info("Outbox cleanup removed {} rows", deleted);
        }
    }

    double lagSeconds() {
        try {
            OffsetDateTime oldest = jdbc.sql("SELECT min(created_at) FROM outbox_event WHERE published_at IS NULL")
                    .query(OffsetDateTime.class)
                    .optional()
                    .orElse(null);
            if (oldest == null) {
                return 0;
            }
            return Math.max(
                    0, Duration.between(oldest.toInstant(), Instant.now(clock)).toSeconds());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
