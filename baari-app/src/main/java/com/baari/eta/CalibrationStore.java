package com.baari.eta;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps each clinic's range calibration factor (PLAN §8.5, ADR 0012). Once a night it measures the
 * coverage of the last {@value RangeCalibration#WINDOW_DAYS} days of logged predictions and moves
 * the factor one step with {@link RangeCalibration#adjust}. The logged ranges were already widened
 * by the factor in use, so the loop corrects itself: too wide and the factor comes back down.
 *
 * <p>Several instances may run the job the same night. The upsert only replaces a row written on an
 * earlier day, so a clinic moves at most one step per day whoever runs first.
 */
@Component
class CalibrationStore {

    private static final Logger log = LoggerFactory.getLogger(CalibrationStore.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MeterRegistry meters;

    CalibrationStore(JdbcClient jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.meters = meters;
    }

    /** The clinic's current factor; {@link RangeCalibration#START} if it was never calibrated. */
    double factor(UUID clinicId) {
        return jdbc.sql("SELECT factor FROM eta_calibration WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId)
                .query(Double.class)
                .optional()
                .orElse(RangeCalibration.START);
    }

    private record Coverage(UUID clinicId, long covered, long total) {}

    /** The nightly job: one step for every clinic with scored predictions in the window. */
    @Scheduled(cron = "${baari.eta.calibration-cron:0 45 2 * * *}", zone = "UTC")
    void recalibrate() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant since = now.minus(Duration.ofDays(RangeCalibration.WINDOW_DAYS));
        List<Coverage> coverages = jdbc.sql("""
                        SELECT clinic_id,
                               count(*) FILTER (WHERE actual_wait_s BETWEEN range_min_s AND range_max_s) AS covered,
                               count(*) AS total
                        FROM eta_prediction
                        WHERE actual_wait_s IS NOT NULL AND predicted_at >= :since
                        GROUP BY clinic_id
                        """)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query((rs, n) ->
                        new Coverage(rs.getObject("clinic_id", UUID.class), rs.getLong("covered"), rs.getLong("total")))
                .list();
        for (Coverage c : coverages) {
            double current = factor(c.clinicId());
            double next = RangeCalibration.adjust(current, c.covered(), c.total());
            int written = jdbc.sql("""
                            INSERT INTO eta_calibration (clinic_id, factor, coverage, predictions, updated_on)
                            VALUES (:clinicId, :factor, :coverage, :total, :today)
                            ON CONFLICT (clinic_id) DO UPDATE
                              SET factor = EXCLUDED.factor, coverage = EXCLUDED.coverage,
                                  predictions = EXCLUDED.predictions, updated_on = EXCLUDED.updated_on
                              WHERE eta_calibration.updated_on < EXCLUDED.updated_on
                            """)
                    .param("clinicId", c.clinicId())
                    .param("factor", next)
                    .param("coverage", c.covered() / (double) c.total())
                    .param("total", c.total())
                    .param("today", today)
                    .update();
            if (written > 0) {
                meters.summary("baari.eta.calibration.factor").record(next);
                log.info(
                        "ETA calibration for clinic {}: coverage {}/{} -> factor {} (was {})",
                        c.clinicId(),
                        c.covered(),
                        c.total(),
                        next,
                        current);
            }
        }
    }
}
