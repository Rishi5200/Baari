package com.baari.queue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only history of finished consultations for the ETA module (PLAN §8.3). Consultations that
 * were closed automatically at the end of the day are left out: their end time is the closing
 * time, not the end of the consultation.
 */
@Service
public class ServiceHistory {

    private final JdbcClient jdbc;

    ServiceHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The most recent finished consultations of a clinic, newest first.
     *
     * @param since only consultations that started at or after this time
     */
    @Transactional(readOnly = true)
    public List<ServiceSample> recent(UUID clinicId, Instant since, int limit) {
        Objects.requireNonNull(clinicId, "clinicId");
        Objects.requireNonNull(since, "since");
        return jdbc.sql("""
                        SELECT served_by_doctor_id, visit_type, consult_started_at,
                               CAST(EXTRACT(EPOCH FROM (completed_at - consult_started_at)) AS integer) AS service_s
                          FROM token
                         WHERE clinic_id = :clinicId
                           AND status = 'COMPLETED'
                           AND consult_started_at >= :since
                           AND completed_at IS NOT NULL
                           AND NOT (:autoClosed = ANY (flags))
                         ORDER BY consult_started_at DESC
                         LIMIT :limit
                        """)
                .param("clinicId", clinicId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .param("autoClosed", TokenFlag.AUTO_CLOSED.name())
                .param("limit", limit)
                .query((rs, row) -> new ServiceSample(
                        rs.getObject("served_by_doctor_id", UUID.class),
                        VisitType.valueOf(rs.getString("visit_type")),
                        rs.getObject("consult_started_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("service_s")))
                .list();
    }
}
