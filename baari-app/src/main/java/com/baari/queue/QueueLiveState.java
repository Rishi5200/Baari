package com.baari.queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the ETA module needs to know about one queue today, with no patient details (no names,
 * phones or public codes). Built from the database on each call.
 *
 * @param seq the day's event sequence number; it grows with every change, so equal values mean the
 *     queue has not changed
 * @param tokens every token of the day, in no particular order
 */
public record QueueLiveState(
        UUID clinicId,
        UUID queueId,
        UUID queueDayId,
        QueueDayStatus status,
        long seq,
        NextTokenPolicy.State policyState,
        NextTokenPolicy.Settings policySettings,
        List<LiveToken> tokens) {

    public QueueLiveState {
        tokens = List.copyOf(tokens);
    }

    /**
     * @param view the ordering data the policy needs
     * @param calledAt null until called
     * @param consultStartedAt null until the consultation starts
     * @param servedByDoctorId the doctor it was called to or started with; null while waiting
     */
    public record LiveToken(
            TokenView view, VisitType visitType, Instant calledAt, Instant consultStartedAt, UUID servedByDoctorId) {}
}
