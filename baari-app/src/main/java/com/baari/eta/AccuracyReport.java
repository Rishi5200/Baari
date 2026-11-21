package com.baari.eta;

import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.ForbiddenException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The clinic admin's "Prediction accuracy" page (PLAN §8.7, Phase 7): how far off the shown waits
 * were over the last {@value #DAYS} days, per estimator, plus the range calibration factor and the
 * trained models. Only predictions with a known actual wait count; no-shows are reported separately.
 */
@Service
public class AccuracyReport {

    static final int DAYS = 14;

    private static final java.time.format.DateTimeFormatter TRAINED_AT = java.time.format.DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final CalibrationStore calibration;
    private final MlModelStore models;
    private final Clock clock;

    AccuracyReport(JdbcClient jdbc, CalibrationStore calibration, MlModelStore models, Clock clock) {
        this.jdbc = jdbc;
        this.calibration = calibration;
        this.models = models;
        this.clock = clock;
    }

    /** Live accuracy of one estimator, from the prediction log. */
    public record EstimatorAccuracy(String estimator, int predictions, double maeMinutes, double coverage) {}

    /** A trained model as the page lists it. */
    public record ModelRow(
            int version,
            String algorithm,
            String status,
            String trainedAt,
            LocalDate testFrom,
            LocalDate testTo,
            int trainingSamples,
            Double serviceMaeMinutes,
            Double waitMaeMinutes,
            Double waitCoverage,
            String baseline,
            Double baselineWaitMaeMinutes) {}

    /**
     * @param noShowRate share of called patients who did not come, over the same days
     */
    public record View(
            List<EstimatorAccuracy> estimators, double calibrationFactor, double noShowRate, List<ModelRow> models) {}

    public View view(StaffPrincipal caller) {
        if (caller.role() != StaffRole.CLINIC_ADMIN || caller.clinicId() == null) {
            throw new ForbiddenException();
        }
        UUID clinicId = caller.clinicId();
        OffsetDateTime since = OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(DAYS)), ZoneOffset.UTC);
        List<EstimatorAccuracy> estimators = jdbc.sql("""
                        SELECT estimator, count(*) AS n,
                               avg(abs(predicted_wait_s - actual_wait_s)) / 60.0 AS mae_min,
                               avg(CASE WHEN actual_wait_s BETWEEN range_min_s AND range_max_s THEN 1.0 ELSE 0.0 END)
                                   AS coverage
                          FROM eta_prediction
                         WHERE clinic_id = :clinicId AND actual_wait_s IS NOT NULL AND predicted_at >= :since
                         GROUP BY estimator
                         ORDER BY estimator
                        """)
                .param("clinicId", clinicId)
                .param("since", since)
                .query((rs, n) -> new EstimatorAccuracy(
                        rs.getString("estimator"), rs.getInt("n"), rs.getDouble("mae_min"), rs.getDouble("coverage")))
                .list();
        double noShowRate = jdbc.sql("""
                        SELECT coalesce(avg(CASE WHEN status = 'NO_SHOW' THEN 1.0 ELSE 0.0 END), 0)
                          FROM token
                         WHERE clinic_id = :clinicId AND called_at >= :since
                        """)
                .param("clinicId", clinicId)
                .param("since", since)
                .query(Double.class)
                .single();
        List<ModelRow> rows = models.list(clinicId, 20).stream()
                .map(m -> new ModelRow(
                        m.version(),
                        m.algorithm(),
                        m.status(),
                        TRAINED_AT.format(m.trainedAt()),
                        m.testFrom(),
                        m.testTo(),
                        m.trainingSamples(),
                        minutes(m.metrics(), "serviceMaeS", 60),
                        minutes(m.metrics(), "waitMaeMin", 1),
                        minutes(m.metrics(), "waitCoverage", 1),
                        (String) m.metrics().get("baseline"),
                        minutes(m.metrics(), "baselineWaitMaeMin", 1)))
                .toList();
        return new View(estimators, calibration.factor(clinicId), noShowRate, rows);
    }

    private static Double minutes(Map<String, Object> metrics, String key, double divisor) {
        Object v = metrics.get(key);
        return v instanceof Number n ? n.doubleValue() / divisor : null;
    }
}
