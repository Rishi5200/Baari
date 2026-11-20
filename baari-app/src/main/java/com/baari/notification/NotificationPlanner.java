package com.baari.notification;

import com.baari.eta.EtaService;
import com.baari.eta.TokenEstimate;
import com.baari.events.EventHeaders;
import com.baari.events.EventTopics;
import com.baari.events.ProcessedEvents;
import com.baari.queue.QueueQueryService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns queue events into notification rows (PLAN §11.3, ADR 0011). It reacts to a Kafka event,
 * works out who should now be told something, and inserts {@code PENDING} rows; sending is the
 * dispatcher's job. {@code UNIQUE (token_id, kind, channel)} makes planning idempotent, and the
 * {@code processed_event} claim stops a redelivered event from being handled twice.
 *
 * <ul>
 *   <li>{@code TokenCalled}: the patient is told it is their turn.
 *   <li>{@code QueueDayClosed}: patients still open are told the queue has closed.
 *   <li>any other event: positions may have moved, so everyone within {@code notifyAheadCount} who
 *       has not been told yet gets a "come back" message.
 * </ul>
 */
@Component
class NotificationPlanner {

    static final String CONSUMER = "notification-planner";

    private final ProcessedEvents processed;
    private final QueueQueryService queues;
    private final EtaService eta;
    private final List<NotificationChannel> channels;
    private final NotificationProperties props;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    NotificationPlanner(
            ProcessedEvents processed,
            QueueQueryService queues,
            EtaService eta,
            List<NotificationChannel> channels,
            NotificationProperties props,
            JdbcClient jdbc,
            TransactionTemplate tx,
            JsonMapper json,
            Clock clock) {
        this.processed = processed;
        this.queues = queues;
        this.eta = eta;
        this.channels = channels;
        this.props = props;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    @KafkaListener(topics = EventTopics.QUEUE_EVENTS, groupId = CONSUMER)
    void on(
            @Payload String payload,
            @Header(EventHeaders.EVENT_ID) byte[] eventId,
            @Header(EventHeaders.EVENT_TYPE) byte[] eventType) {
        UUID id = UUID.fromString(new String(eventId, StandardCharsets.UTF_8));
        String type = new String(eventType, StandardCharsets.UTF_8);
        tx.executeWithoutResult(status -> {
            if (processed.claim(CONSUMER, id)) {
                plan(type, json.readTree(payload));
            }
        });
    }

    private void plan(String type, JsonNode event) {
        switch (type) {
            case "TokenCalled" -> {
                planFor(UUID.fromString(event.get("tokenId").asString()), NotificationKind.CALLED);
                // calling someone also moves everyone else up
                planNearTurn(UUID.fromString(event.get("queueDayId").asString()));
            }
            case "QueueDayClosed" ->
                queues.expiredTokenIds(UUID.fromString(event.get("queueDayId").asString()))
                        .forEach(token -> planFor(token, NotificationKind.QUEUE_CLOSED));
            case "QueueDayOpened", "QueueDayPaused", "QueueDayResumed" -> {
                // no patient-facing message; positions did not change
            }
            default -> planNearTurn(UUID.fromString(event.get("queueDayId").asString()));
        }
    }

    private void planNearTurn(UUID queueDayId) {
        queues.queueIdOfDay(queueDayId).ifPresent(queueId -> {
            Map<UUID, TokenEstimate> estimates = eta.estimates(queueId);
            estimates.forEach((tokenId, estimate) -> {
                if (estimate.position() <= props.notifyAheadCount()) {
                    planFor(tokenId, NotificationKind.NEAR_TURN);
                }
            });
        });
    }

    /** One PENDING row per channel that can reach the token; a row that exists is left alone. */
    private void planFor(UUID tokenId, NotificationKind kind) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (NotificationChannel channel : channels) {
            if (!channel.canReach(tokenId)) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO notification (id, token_id, kind, channel, status, next_attempt_at, created_at)
                            VALUES (:id, :tokenId, :kind, :channel, 'PENDING', :now, :now)
                            ON CONFLICT (token_id, kind, channel) DO NOTHING
                            """)
                    .param("id", com.baari.common.Ids.newId())
                    .param("tokenId", tokenId)
                    .param("kind", kind.name())
                    .param("channel", channel.name())
                    .param("now", now)
                    .update();
        }
    }
}
