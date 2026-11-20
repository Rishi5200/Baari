package com.baari.events;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes consumers idempotent (PLAN §11.3). A consumer calls {@link #claim} first, in the same
 * transaction as its side effects: the first call for an (consumer, event) pair inserts the row and
 * returns true; a redelivery finds the row and returns false, so the consumer does nothing. If the
 * side effects roll back, the claim rolls back with them and the retry can run again.
 */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;
    private final Clock clock;

    ProcessedEvents(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String consumer, UUID eventId) {
        return jdbc.sql("""
                                INSERT INTO processed_event (consumer, event_id, processed_at)
                                VALUES (:consumer, :eventId, :at)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("consumer", consumer)
                        .param("eventId", eventId)
                        .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
