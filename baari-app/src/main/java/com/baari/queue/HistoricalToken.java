package com.baari.queue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One token of a past day with its timestamps, for training and replaying wait-time models
 * (PLAN §8.6). No patient details. Times are null when the step never happened.
 *
 * @param doctorId the doctor who called or saw the patient; null if nobody did
 * @param autoClosed the consultation was closed by the end-of-day job, so its end time is not real
 */
public record HistoricalToken(
        UUID id,
        UUID queueDayId,
        LocalDate serviceDate,
        UUID doctorId,
        int number,
        VisitType visitType,
        Priority priority,
        TokenStatus status,
        Instant joinedAt,
        Instant calledAt,
        Instant consultStartedAt,
        Instant completedAt,
        Instant cancelledAt,
        boolean autoClosed) {

    public HistoricalToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(queueDayId, "queueDayId");
        Objects.requireNonNull(serviceDate, "serviceDate");
        Objects.requireNonNull(visitType, "visitType");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }

    /** A real, finished consultation whose length can be learned from. */
    public boolean hasServiceTime() {
        return status == TokenStatus.COMPLETED && consultStartedAt != null && completedAt != null && !autoClosed;
    }

    /** Was this patient waiting in line at {@code at}? Uses only what was known at that moment. */
    public boolean waitingAt(Instant at) {
        return !joinedAt.isAfter(at)
                && (calledAt == null || calledAt.isAfter(at))
                && (cancelledAt == null || cancelledAt.isAfter(at));
    }
}
