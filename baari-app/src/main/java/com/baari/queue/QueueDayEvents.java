package com.baari.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * Queue day events (PLAN §11.1). {@code QueueDayResumed} is not in the PLAN list; it is added so
 * that every status change of a day has an event and listeners never have to guess.
 */
public final class QueueDayEvents {

    private QueueDayEvents() {}

    public record QueueDayOpened(
            UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq, UUID queueId)
            implements QueueEvent {}

    public record QueueDayPaused(UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq)
            implements QueueEvent {}

    public record QueueDayResumed(UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq)
            implements QueueEvent {}

    /**
     * @param expired tokens that were still waiting, called or no-show and are now EXPIRED
     * @param autoCompleted tokens that were in consultation and are now COMPLETED (flag AUTO_CLOSED)
     */
    public record QueueDayClosed(
            UUID eventId, Instant occurredAt, UUID clinicId, UUID queueDayId, long seq, int expired, int autoCompleted)
            implements QueueEvent {}
}
