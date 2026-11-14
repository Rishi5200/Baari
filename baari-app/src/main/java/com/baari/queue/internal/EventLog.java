package com.baari.queue.internal;

import com.baari.common.Ids;
import com.baari.queue.ActorType;
import com.baari.queue.QueueChanged;
import com.baari.queue.QueueEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the two records every queue change leaves behind (PLAN §4, §11.2), inside the caller's
 * transaction: a {@code token_event} row (audit trail, with the day's next sequence number) and an
 * {@code outbox_event} row (relayed to Kafka from Phase 5). Because both commit or roll back with
 * the change itself, an event is never lost or published for a change that didn't happen.
 *
 * <p>Only ids, statuses and counts go into event data; never names or phone numbers.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EventLog {

    private static final String QUEUE_DAY = "QueueDay";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ApplicationEventPublisher publisher;

    EventLog(JdbcClient jdbc, JsonMapper json, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.json = json;
        this.publisher = publisher;
    }

    /**
     * Appends a {@code token_event} row. The caller must hold the day's row lock (that is what
     * makes {@link QueueDayEntity#nextEventSeq()} safe).
     *
     * @param tokenId null for day-level events
     * @param actorId the staff user, or null for patients and the system
     * @return the sequence number the event got
     */
    public long append(
            QueueDayEntity day,
            UUID tokenId,
            EventType type,
            ActorType actorType,
            UUID actorId,
            Instant at,
            Map<String, ?> data) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorType, "actorType");
        long seq = day.nextEventSeq();
        jdbc.sql("""
                        INSERT INTO token_event (id, token_id, queue_day_id, seq, type, actor_type, actor_id, at, data)
                        VALUES (:id, :tokenId, :queueDayId, :seq, :type, :actorType, :actorId, :at, CAST(:data AS jsonb))
                        """)
                .param("id", Ids.newId())
                .param("tokenId", tokenId)
                .param("queueDayId", day.getId())
                .param("seq", seq)
                .param("type", type.name())
                .param("actorType", actorType.name())
                .param("actorId", actorId)
                .param("at", Sql.timestamptz(at))
                .param("data", json.writeValueAsString(data))
                .update();
        // Listeners run after commit (PLAN §10.2), so a rolled-back change is never announced.
        publisher.publishEvent(new QueueChanged(day.getQueueId(), day.getId(), seq));
        return seq;
    }

    /**
     * Writes the event to the outbox. The aggregate is the queue day, and the day id is the Kafka
     * partition key, so all events of one queue day stay in order for consumers.
     */
    public void publish(QueueEvent event) {
        jdbc.sql("""
                        INSERT INTO outbox_event (id, aggregate_type, aggregate_id, partition_key, event_type, payload, created_at)
                        VALUES (:id, :aggregateType, :aggregateId, :partitionKey, :eventType, CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("id", event.eventId())
                .param("aggregateType", QUEUE_DAY)
                .param("aggregateId", event.queueDayId())
                .param("partitionKey", event.queueDayId().toString())
                .param("eventType", event.getClass().getSimpleName())
                .param("payload", json.writeValueAsString(event))
                .param("createdAt", Sql.timestamptz(event.occurredAt()))
                .update();
    }
}
