package com.baari.queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A token as the staff console sees it. Includes the patient's name and the last four phone
 * digits (staff need them to call the right person), never the full number or the public code.
 */
public record StaffTokenView(
        UUID id,
        String displayCode,
        int number,
        TokenStatus status,
        Priority priority,
        VisitType visitType,
        TokenSource source,
        String patientName,
        String phoneLast4,
        List<TokenFlag> flags,
        UUID servedByDoctorId,
        Instant joinedAt,
        Instant calledAt,
        Instant consultStartedAt,
        Instant completedAt,
        Instant cancelledAt) {

    public StaffTokenView {
        flags = List.copyOf(flags);
    }

    /** Names and phones may end up in logs by accident; keep them out. */
    @Override
    public String toString() {
        return "StaffTokenView[id=" + id + ", displayCode=" + displayCode + ", status=" + status + "]";
    }
}
