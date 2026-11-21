package com.baari.queue;

import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Every token of a clinic's days from {@code from} to {@code to} (inclusive), in day and number
     * order. Used to train and evaluate wait-time models; no patient details are read.
     */
    @Transactional(readOnly = true)
    public List<HistoricalToken> tokens(UUID clinicId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(clinicId, "clinicId");
        return jdbc.sql("""
                        SELECT t.id, t.queue_day_id, d.service_date, t.served_by_doctor_id, t.number, t.visit_type,
                               t.priority, t.status, t.joined_at, t.called_at, t.consult_started_at,
                               t.completed_at, t.cancelled_at, (:autoClosed = ANY (t.flags)) AS auto_closed
                          FROM token t
                          JOIN queue_day d ON d.id = t.queue_day_id
                         WHERE t.clinic_id = :clinicId
                           AND d.service_date BETWEEN :from AND :to
                         ORDER BY d.service_date, t.queue_day_id, t.number
                        """)
                .param("clinicId", clinicId)
                .param("from", from)
                .param("to", to)
                .param("autoClosed", TokenFlag.AUTO_CLOSED.name())
                .query((rs, row) -> new HistoricalToken(
                        rs.getObject("id", UUID.class),
                        rs.getObject("queue_day_id", UUID.class),
                        rs.getObject("service_date", LocalDate.class),
                        rs.getObject("served_by_doctor_id", UUID.class),
                        rs.getInt("number"),
                        VisitType.valueOf(rs.getString("visit_type")),
                        Priority.valueOf(rs.getString("priority")),
                        TokenStatus.valueOf(rs.getString("status")),
                        instant(rs.getObject("joined_at", OffsetDateTime.class)),
                        instant(rs.getObject("called_at", OffsetDateTime.class)),
                        instant(rs.getObject("consult_started_at", OffsetDateTime.class)),
                        instant(rs.getObject("completed_at", OffsetDateTime.class)),
                        instant(rs.getObject("cancelled_at", OffsetDateTime.class)),
                        rs.getBoolean("auto_closed")))
                .list();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
