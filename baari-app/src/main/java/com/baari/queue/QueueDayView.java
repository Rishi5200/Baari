package com.baari.queue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Read model of a queue day. {@code closedAt} is null until the day closes. */
public record QueueDayView(
        UUID id,
        UUID queueId,
        UUID clinicId,
        LocalDate serviceDate,
        QueueDayStatus status,
        int lastNumber,
        long lastEventSeq,
        Instant openedAt,
        Instant closedAt) {}
