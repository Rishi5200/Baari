package com.baari.queue.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Queue day SQL that JPA can't express. */
@Component
public class QueueDaySql {

    private final JdbcClient jdbc;

    QueueDaySql(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the day as OPEN unless a row for this queue and date already exists. Returns true if
     * this call created it.
     *
     * <p>Why {@code ON CONFLICT DO NOTHING} rather than "select, then insert": two staff opening the
     * day at the same moment would both see no row and one insert would fail. Here the second
     * insert waits for the first to commit and then does nothing, so open is idempotent even
     * under a race.
     */
    public boolean insertIfAbsent(UUID id, UUID queueId, UUID clinicId, LocalDate serviceDate, Instant openedAt) {
        int inserted = jdbc.sql("""
                        INSERT INTO queue_day (id, queue_id, clinic_id, service_date, status, opened_at)
                        VALUES (:id, :queueId, :clinicId, :serviceDate, 'OPEN', :openedAt)
                        ON CONFLICT (queue_id, service_date) DO NOTHING
                        """)
                .param("id", id)
                .param("queueId", queueId)
                .param("clinicId", clinicId)
                .param("serviceDate", serviceDate)
                .param("openedAt", Sql.timestamptz(openedAt))
                .update();
        return inserted == 1;
    }
}
