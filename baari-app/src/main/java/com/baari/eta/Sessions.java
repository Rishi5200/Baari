package com.baari.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Where a doctor is within their working session, worked out from when their consultations
 * started (PLAN §8.6 features). The app stores no session timetable, so a session is a run of
 * consultations with no pause longer than {@link #GAP} between two starts: a morning and an evening
 * session are two sessions, a ten-minute tea break is not.
 *
 * <p>Pure, and used by both training and prediction so the two count the same way.
 */
public final class Sessions {

    /** A pause between two consultation starts longer than this begins a new session. */
    public static final Duration GAP = Duration.ofMinutes(90);

    private Sessions() {}

    /**
     * @param started how many consultations of the current session have started up to now
     * @param sessionStart when the first of them started; null if none has
     * @param lastStart when the most recent one started; null if none has
     */
    public record SoFar(int started, Instant sessionStart, Instant lastStart) {
        public static final SoFar NONE = new SoFar(0, null, null);

        /** Where a consultation starting at {@code at} falls: a new session after a long pause. */
        public SoFar next(Instant at) {
            if (lastStart == null || Duration.between(lastStart, at).compareTo(GAP) > 0) {
                return new SoFar(1, at, at);
            }
            return new SoFar(started + 1, sessionStart, at);
        }

        /** Minutes since the session's first consultation, for a consultation starting at {@code at}. */
        public int minutesInto(Instant at) {
            return sessionStart == null
                    ? 0
                    : (int) Math.max(0, Duration.between(sessionStart, at).toMinutes());
        }
    }

    /**
     * The session state after the given consultation starts, in time order. Starts after {@code now}
     * are ignored, so the answer never depends on the future.
     */
    public static SoFar soFar(List<Instant> startsAscending, Instant now) {
        SoFar s = SoFar.NONE;
        for (Instant start : startsAscending) {
            if (start.isAfter(now)) {
                break;
            }
            s = s.next(start);
        }
        // a long pause since the last start means the next consultation opens a new session
        if (s.lastStart() != null && Duration.between(s.lastStart(), now).compareTo(GAP) > 0) {
            return SoFar.NONE;
        }
        return s;
    }
}
