package com.baari.eta;

import com.baari.queue.Priority;
import com.baari.queue.VisitType;
import java.time.DayOfWeek;
import java.util.Objects;
import java.util.UUID;

/**
 * What an estimator may look at to guess one consultation's length (PLAN §8.2, §8.6). Everything
 * here is known before the consultation starts; nothing about the consultation itself or anything
 * later. Rolling statistics use the first three fields; the ML model uses all of them.
 *
 * @param doctorId the doctor expected to see the patient; null if not known
 * @param hourOfDay the hour (0 to 23, clinic time) the consultation is expected to start
 * @param positionInSession 1 for the doctor's first patient of the session, 2 for the second, ...
 * @param minutesIntoSession minutes from the session's first consultation to this one's start
 * @param queueLength patients still waiting in the queue when this consultation starts
 */
public record ServiceContext(
        UUID doctorId,
        VisitType visitType,
        int hourOfDay,
        Priority priority,
        DayOfWeek dayOfWeek,
        int positionInSession,
        int minutesIntoSession,
        int queueLength) {

    public ServiceContext {
        Objects.requireNonNull(visitType, "visitType");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(dayOfWeek, "dayOfWeek");
        if (hourOfDay < 0 || hourOfDay > 23) {
            throw new IllegalArgumentException("hourOfDay must be 0..23");
        }
        if (positionInSession < 1 || minutesIntoSession < 0 || queueLength < 0) {
            throw new IllegalArgumentException(
                    "need positionInSession >= 1, minutesIntoSession >= 0, queueLength >= 0");
        }
    }

    /** Only what Rolling statistics need; the rest takes neutral values. */
    public ServiceContext(UUID doctorId, VisitType visitType, int hourOfDay) {
        this(doctorId, visitType, hourOfDay, Priority.NORMAL, DayOfWeek.MONDAY, 1, 0, 0);
    }
}
