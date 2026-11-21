package com.baari.queue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes patients' personal data once it is no longer needed (PLAN §9.4, designed with DPDP
 * principles in mind). Daily, tokens that joined more than {@code baari.privacy.pii-retention-days}
 * (30) days ago lose their name and phone (ciphertext, hash and last four digits). The timings stay:
 * they identify nobody and are what the wait-time models learn from.
 *
 * <p>Works in batches with {@code FOR UPDATE SKIP LOCKED}, so two instances share the work and a
 * long backlog never holds one huge transaction.
 */
@Component
class PiiRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PiiRetentionJob.class);
    static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration retention;

    PiiRetentionJob(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${baari.privacy.pii-retention-days:30}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("baari.privacy.pii-retention-days must be >= 1");
        }
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(cron = "${baari.privacy.retention-cron:0 40 3 * * *}")
    void nightly() {
        int purged = purge();
        if (purged > 0) {
            log.info("Retention: removed personal data from {} tokens", purged);
        }
    }

    /** Purges every token past the retention period; returns how many. */
    int purge() {
        int total = 0;
        while (true) {
            Integer n = tx.execute(status -> purgeBatch());
            total += n == null ? 0 : n;
            if (n == null || n < BATCH) {
                return total;
            }
        }
    }

    private int purgeBatch() {
        Instant now = clock.instant();
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(now.minus(retention), ZoneOffset.UTC);
        List<UUID> ids = jdbc.sql("""
                        SELECT id FROM token
                         WHERE pii_purged_at IS NULL AND joined_at < :cutoff
                         ORDER BY joined_at
                         LIMIT :batch
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("cutoff", cutoff)
                .param("batch", BATCH)
                .query(UUID.class)
                .list();
        if (ids.isEmpty()) {
            return 0;
        }
        // version + 1: a JPA entity loaded before the purge must not write the name back
        jdbc.sql("""
                        UPDATE token
                           SET patient_name = NULL, phone_enc = NULL, phone_hash = NULL, phone_last4 = NULL,
                               pii_purged_at = :now, version = version + 1
                         WHERE id IN (:ids)
                        """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("ids", ids)
                .update();
        events.publishEvent(new PiiPurged(ids));
        return ids.size();
    }
}
