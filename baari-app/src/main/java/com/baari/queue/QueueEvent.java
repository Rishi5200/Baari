package com.baari.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * A domain event of the queue module (PLAN §11.1). Written to the outbox in the same transaction
 * as the change it describes; {@code eventId} is also the outbox row id, so consumers can dedupe.
 * {@code seq} is the queue day's event sequence number, which orders events for live updates.
 */
public sealed interface QueueEvent
        permits QueueDayEvents.QueueDayOpened,
                QueueDayEvents.QueueDayPaused,
                QueueDayEvents.QueueDayResumed,
                QueueDayEvents.QueueDayClosed,
                TokenEvents.TokenJoined {

    UUID eventId();

    Instant occurredAt();

    UUID clinicId();

    UUID queueDayId();

    long seq();
}
