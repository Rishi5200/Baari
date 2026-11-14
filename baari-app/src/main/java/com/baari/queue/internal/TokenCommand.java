package com.baari.queue.internal;

import com.baari.queue.ActorType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Something that may change a token's status. Each command carries the facts the state machine
 * needs to decide, so {@link TokenStateMachine} stays a pure function with no clock or database.
 */
sealed interface TokenCommand {

    /** The policy picked this token as the next one. */
    record CallNext() implements TokenCommand {}

    /** Staff picked this token by hand, skipping the policy (allowed, but flagged). */
    record CallSpecific() implements TokenCommand {}

    record Start() implements TokenCommand {}

    record Complete() implements TokenCommand {}

    /**
     * @param calledAt when the token was called
     * @param now the current time from the injected clock
     * @param grace how long a called patient has to show up ({@code noShowGraceMinutes})
     * @param force staff override that skips the grace check
     */
    record MarkNoShow(Instant calledAt, Instant now, Duration grace, boolean force) implements TokenCommand {
        public MarkNoShow {
            Objects.requireNonNull(calledAt, "calledAt");
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(grace, "grace");
        }
    }

    /**
     * @param serviceDate the date of the token's queue day
     * @param today today in the clinic's time zone
     */
    record Recall(LocalDate serviceDate, LocalDate today) implements TokenCommand {
        public Recall {
            Objects.requireNonNull(serviceDate, "serviceDate");
            Objects.requireNonNull(today, "today");
        }
    }

    record Cancel(ActorType by) implements TokenCommand {
        public Cancel {
            Objects.requireNonNull(by, "by");
        }
    }

    /** Priority can change while waiting; the status itself stays the same. */
    record SetPriority() implements TokenCommand {}

    /** The queue day is closing; every token still open must end. */
    record CloseDay() implements TokenCommand {}
}
