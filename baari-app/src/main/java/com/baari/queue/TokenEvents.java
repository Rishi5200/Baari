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

    /** @param override true when staff picked this patient out of order (not call-next) */
    public record TokenCalled(
            UUID eventId,
            Instant occurredAt,
            UUID clinicId,
            UUID queueDayId,
            long seq,
            UUID tokenId,
            UUID doctorId,
            boolean override)
            implements QueueEvent {}

    /** @param directStart true when the patient went in without being called first */
    public record ConsultStarted(
            UUID eventId,
            Instant occurredAt,
            UUID clinicId,
            UUID queueDayId,
            long seq,
            UUID tokenId,
            UUID doctorId,
            boolean directStart)
            implements QueueEvent {}

    public record ConsultCompleted(
            UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq, UUID tokenId, UUID doctorId)
            implements QueueEvent {}

    /** @param forced true when staff marked it before the grace period ended */
    public record TokenNoShow(
            UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq, UUID tokenId, boolean forced)
            implements QueueEvent {}

    public record TokenRecalled(
            UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq, UUID tokenId, long sortKey)
            implements QueueEvent {}

    public record TokenCancelled(
            UUID eventId,
            Instant occurredAt,
            UUID clinicId,
            UUID queueDayId,
            long seq,
            UUID tokenId,
            ActorType cancelledBy)
            implements QueueEvent {}

    public record TokenPriorityChanged(
            UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq, UUID tokenId, Priority priority)
            implements QueueEvent {}
}
