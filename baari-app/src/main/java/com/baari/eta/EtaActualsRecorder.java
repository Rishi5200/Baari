package com.baari.eta;

import com.baari.events.EventHeaders;
import com.baari.events.EventTopics;
import com.baari.events.ProcessedEvents;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills in how long a patient really waited when their consultation starts (PLAN §11.3). It reads
 * {@code ConsultStarted} from Kafka, so it works the same on any instance; {@code processed_event}
 * makes a redelivered event do nothing, which keeps the error metrics from counting a patient twice.
 */
@Component
class EtaActualsRecorder {

    private static final String NAME = "eta-actuals-recorder";

    /** The consumer name used for dedup; also the Kafka group (a prefix lets tests keep contexts apart). */
    private final String consumer;

    private final PredictionLog predictions;
    private final ProcessedEvents processed;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    EtaActualsRecorder(
            PredictionLog predictions,
            ProcessedEvents processed,
            TransactionTemplate tx,
            JsonMapper json,
            @Value("${baari.events.group-prefix:}") String groupPrefix) {
        this.consumer = groupPrefix + NAME;
        this.predictions = predictions;
        this.processed = processed;
        this.tx = tx;
        this.json = json;
    }

    @KafkaListener(topics = EventTopics.QUEUE_EVENTS, groupId = "${baari.events.group-prefix:}" + NAME)
    void on(
            @Payload String payload,
            @Header(EventHeaders.EVENT_ID) byte[] eventId,
            @Header(EventHeaders.EVENT_TYPE) byte[] eventType) {
        if (!"ConsultStarted".equals(new String(eventType, StandardCharsets.UTF_8))) {
            return;
        }
        UUID id = UUID.fromString(new String(eventId, StandardCharsets.UTF_8));
        JsonNode event = json.readTree(payload);
        tx.executeWithoutResult(status -> {
            if (processed.claim(consumer, id)) {
                predictions.recordActuals(
                        UUID.fromString(event.get("tokenId").asString()),
                        Instant.parse(event.get("occurredAt").asString()));
            }
        });
    }
}
