package com.baari.eta;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves and loads trained models ({@code ml_model}, ADR 0013). Loading a champion never fails the
 * caller: a model that cannot be read is logged as an alert and counted
 * ({@code baari.eta.ml.load_failures}), and the clinic keeps using Rolling statistics.
 */
@Component
class MlModelStore {

    private static final Logger log = LoggerFactory.getLogger(MlModelStore.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MeterRegistry meters;

    MlModelStore(JdbcClient jdbc, JsonMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.json = json;
        this.meters = meters;
    }

    /** Which model is the clinic's champion right now; cheap, so callers can poll it. */
    record ChampionRef(UUID id, int version) {}

    /** A champion ready to predict. */
    record Champion(UUID id, int version, ServiceTimeModel model) {}

    /** One stored model, without its artifact, for the accuracy page. */
    record ModelSummary(
            UUID id,
            int version,
            String algorithm,
            String status,
            Instant trainedAt,
            LocalDate testFrom,
            LocalDate testTo,
            int trainingSamples,
            Map<String, Object> metrics) {}

    /** A model to save. */
    record NewModel(
            UUID clinicId,
            ServiceTimeModel model,
            String status,
            Instant trainedAt,
            LocalDate trainFrom,
            LocalDate trainTo,
            LocalDate testFrom,
            LocalDate testTo,
            int trainingSamples,
            Map<String, Object> metrics) {}

    Optional<ChampionRef> championRef(UUID clinicId) {
        return jdbc.sql("SELECT id, version FROM ml_model WHERE clinic_id = :clinicId AND status = 'CHAMPION'")
                .param("clinicId", clinicId)
                .query((rs, n) -> new ChampionRef(rs.getObject("id", UUID.class), rs.getInt("version")))
                .optional();
    }

    /** Loads a champion by id; empty (with an alert in the log) if its artifact cannot be read. */
    Optional<Champion> load(ChampionRef ref) {
        record Row(String algorithm, byte[] artifact, double smearing, double p20, double p80) {}
        Optional<Row> row = jdbc.sql("""
                        SELECT algorithm, artifact, smearing, residual_p20, residual_p80
                          FROM ml_model WHERE id = :id
                        """)
                .param("id", ref.id())
                .query((rs, n) -> new Row(
                        rs.getString("algorithm"),
                        rs.getBytes("artifact"),
                        rs.getDouble("smearing"),
                        rs.getDouble("residual_p20"),
                        rs.getDouble("residual_p80")))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row r = row.get();
        try {
            ServiceTimeModel model = ServiceTimeModel.load(r.algorithm(), r.artifact(), r.smearing(), r.p20(), r.p80());
            return Optional.of(new Champion(ref.id(), ref.version(), model));
        } catch (RuntimeException e) {
            meters.counter("baari.eta.ml.load_failures").increment();
            log.error(
                    "ALERT: champion model {} (v{}) could not be loaded, using Rolling statistics instead: {}",
                    ref.id(),
                    ref.version(),
                    e.toString());
            return Optional.empty();
        }
    }

    int nextVersion(UUID clinicId) {
        return jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM ml_model WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId)
                .query(Integer.class)
                .single();
    }

    /** Was a model trained for the clinic at or after {@code since}? Stops a second run the same night. */
    boolean trainedSince(UUID clinicId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM ml_model WHERE clinic_id = :clinicId AND trained_at >= :since")
                        .param("clinicId", clinicId)
                        .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** The current champion steps down; call in the same transaction as saving the new one. */
    void retireChampion(UUID clinicId) {
        jdbc.sql("UPDATE ml_model SET status = 'RETIRED' WHERE clinic_id = :clinicId AND status = 'CHAMPION'")
                .param("clinicId", clinicId)
                .update();
    }

    UUID save(NewModel m, int version) {
        UUID id = com.baari.common.Ids.newId();
        jdbc.sql("""
                        INSERT INTO ml_model (id, clinic_id, version, algorithm, status, trained_at, train_from, train_to,
                                              test_from, test_to, training_samples, metrics, smearing, residual_p20,
                                              residual_p80, artifact)
                        VALUES (:id, :clinicId, :version, :algorithm, :status, :trainedAt, :trainFrom, :trainTo,
                                :testFrom, :testTo, :samples, CAST(:metrics AS jsonb), :smearing, :p20, :p80, :artifact)
                        """)
                .param("id", id)
                .param("clinicId", m.clinicId())
                .param("version", version)
                .param("algorithm", m.model().algorithm())
                .param("status", m.status())
                .param("trainedAt", OffsetDateTime.ofInstant(m.trainedAt(), ZoneOffset.UTC))
                .param("trainFrom", m.trainFrom())
                .param("trainTo", m.trainTo())
                .param("testFrom", m.testFrom())
                .param("testTo", m.testTo())
                .param("samples", m.trainingSamples())
                .param("metrics", json.writeValueAsString(m.metrics()))
                .param("smearing", m.model().smearing())
                .param("p20", m.model().residualP20())
                .param("p80", m.model().residualP80())
                .param("artifact", m.model().artifact())
                .update();
        return id;
    }

    /** The clinic's models, newest first. */
    @SuppressWarnings("unchecked")
    List<ModelSummary> list(UUID clinicId, int limit) {
        return jdbc.sql("""
                        SELECT id, version, algorithm, status, trained_at, test_from, test_to, training_samples,
                               CAST(metrics AS text) AS metrics
                          FROM ml_model WHERE clinic_id = :clinicId
                         ORDER BY version DESC
                         LIMIT :limit
                        """)
                .param("clinicId", clinicId)
                .param("limit", limit)
                .query((rs, n) -> new ModelSummary(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version"),
                        rs.getString("algorithm"),
                        rs.getString("status"),
                        rs.getObject("trained_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("test_from", LocalDate.class),
                        rs.getObject("test_to", LocalDate.class),
                        rs.getInt("training_samples"),
                        json.readValue(rs.getString("metrics"), Map.class)))
                .list();
    }
}
