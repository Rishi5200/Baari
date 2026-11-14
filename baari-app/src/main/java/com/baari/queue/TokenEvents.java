package com.baari.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * Token events (PLAN §11.1). They carry ids, numbers and statuses only, never a patient's name or
 * phone number: the outbox is copied to Kafka, where PII would escape the retention purge.
 */
public final class TokenEvents {

    private TokenEvents() {}

    public record TokenJoined(
            UUID eventId,
            Instant occurredAt,
            UUID clinicId,
            UUID queueDayId,
            long seq,
            UUID tokenId,
            int number,
            Priority priority,
            VisitType visitType,
            TokenSource source)
            implements QueueEvent {}
}
