package com.baari.eta;

import com.baari.common.Ids;
import com.baari.queue.QueueChanged;
import com.baari.queue.TokenEvents.ConsultStarted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps the record of how good the predictions are (PLAN §8.7). After a queue change it stores a
 * prediction for every patient who just joined and for patients whose position has first reached
 * 10, 5, 3 or 1; when a consultation starts it fills in how long that patient really waited and
 * reports the error to the metrics.
 *
 * <p>The work happens off the request thread, one queue at a time, and changes that arrive while a
 * run is in progress are merged into one more run (ADR 0010). Doing it on the request thread, after
 * commit, needs a second database connection while the first is still held, which starved the
 * connection pool under 200 concurrent joins. A failure here is logged and swallowed: a prediction
 * log problem must never undo a call-next. Phase 5 moves this to a Kafka consumer.
 */
@Component
class PredictionLog {

    private static final Logger log = LoggerFactory.getLogger(PredictionLog.class);

    /** Positions that get their own prediction row when first reached (PLAN §8.7). */
    private static final List<Integer> MILESTONES = List.of(10, 5, 3, 1);

    private final EtaService eta;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** One lane per queue day, so a day's work runs in order. */
    private final Map<UUID, Lane> lanes = new ConcurrentHashMap<>();

    PredictionLog(EtaService eta, JdbcClient jdbc, Clock clock, MeterRegistry meters) {
        this.eta = eta;
        this.jdbc = jdbc;
        this.clock = clock;
        this.meters = meters;
    }

    /** The pending work of one queue. Everything that touches the fields holds the lane's lock. */
    private final class Lane {
        private UUID queueId;
        private boolean running;
        private boolean projectionPending;
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        synchronized void requestProjection(UUID queueId) {
            this.queueId = queueId;
            projectionPending = true;
            start();
        }

        synchronized void add(Runnable task) {
            tasks.add(task);
            start();
        }

        synchronized boolean idle() {
            return !running && !projectionPending && tasks.isEmpty();
        }

        private void start() {
            if (!running) {
                running = true;
                executor.execute(this::drain);
            }
        }

        /** Runs the pending work until there is none; projections go first so JOIN rows exist before actuals. */
        private void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    if (projectionPending) {
                        projectionPending = false;
                        UUID queue = queueId;
                        next = () -> logPredictions(queue);
                    } else if (!tasks.isEmpty()) {
                        next = tasks.poll();
                    } else {
                        running = false;
                        return;
                    }
                }
                try {
                    next.run();
                } catch (RuntimeException e) {
                    log.warn("ETA prediction log failed for queue {}", queueId, e);
                }
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onQueueChanged(QueueChanged event) {
        lane(event.queueDayId()).requestProjection(event.queueId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onConsultStarted(ConsultStarted event) {
        lane(event.queueDayId()).add(() -> recordActuals(event.tokenId(), event.occurredAt()));
    }

    /** Test hook: waits until every queue's pending work has run. */
    void awaitIdle() {
        long stop = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < stop) {
            if (lanes.values().stream().allMatch(Lane::idle)) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("prediction log did not go idle");
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private Lane lane(UUID queueDayId) {
        return lanes.computeIfAbsent(queueDayId, id -> new Lane());
    }

    private void logPredictions(UUID queueId) {
        eta.projectQueue(queueId).ifPresent(this::logPredictions);
    }

    private void logPredictions(EtaService.Projected projected) {
        if (projected.byToken().isEmpty()) {
            return;
        }
        List<UUID> tokenIds = List.copyOf(projected.byToken().keySet());
        Map<UUID, Set<String>> logged = new HashMap<>();
        Map<UUID, Integer> joinPosition = new HashMap<>();
        jdbc.sql("SELECT token_id, trigger, position FROM eta_prediction WHERE token_id IN (:ids)")
                .param("ids", tokenIds)
                .query((rs, row) -> {
                    UUID tokenId = rs.getObject("token_id", UUID.class);
                    logged.computeIfAbsent(tokenId, id -> new HashSet<>()).add(rs.getString("trigger"));
                    if ("JOIN".equals(rs.getString("trigger"))) {
                        joinPosition.put(tokenId, rs.getInt("position"));
                    }
                    return Boolean.TRUE;
                })
                .list();

        Instant now = clock.instant();
        projected.byToken().forEach((tokenId, p) -> {
            Set<String> done = logged.getOrDefault(tokenId, Set.of());
            if (!done.contains("JOIN")) {
                insert(projected, p, "JOIN", now);
                return;
            }
            // A milestone counts only if the patient started further back than it: someone who
            // joined at position 3 never "reaches" 5.
            for (int milestone : MILESTONES) {
                String trigger = "POSITION_" + milestone;
                if (p.position() <= milestone && joinPosition.get(tokenId) > milestone && !done.contains(trigger)) {
                    insert(projected, p, trigger, now);
                }
            }
        });
    }

    private void insert(EtaService.Projected projected, QueueProjector.Projection p, String trigger, Instant now) {
        jdbc.sql("""
                        INSERT INTO eta_prediction (id, token_id, clinic_id, predicted_at, trigger, position,
                                                    predicted_wait_s, range_min_s, range_max_s, estimator)
                        VALUES (:id, :tokenId, :clinicId, :predictedAt, :trigger, :position,
                                :wait, :rangeMin, :rangeMax, :estimator)
                        ON CONFLICT (token_id, trigger) DO NOTHING
                        """)
                .param("id", Ids.newId())
                .param("tokenId", p.tokenId())
                .param("clinicId", projected.state().clinicId())
                .param("predictedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("trigger", trigger)
                .param("position", p.position())
                .param("wait", p.waitSeconds())
                .param("rangeMin", p.rangeMinSeconds())
                .param("rangeMax", p.rangeMaxSeconds())
                .param("estimator", projected.estimator())
                .update();
    }

    /** Fills {@code actual_wait_s} on the token's predictions that have none yet, and reports the errors. */
    private void recordActuals(UUID tokenId, Instant startedAt) {
        record Row(String trigger, String estimator, int predicted, int min, int max, int actual) {}
        List<Row> rows = jdbc.sql("""
                        UPDATE eta_prediction
                           SET actual_wait_s = GREATEST(0, CAST(EXTRACT(EPOCH FROM (:startedAt - predicted_at)) AS integer))
                         WHERE token_id = :tokenId AND actual_wait_s IS NULL
                     RETURNING trigger, estimator, predicted_wait_s, range_min_s, range_max_s, actual_wait_s
                        """)
                .param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
                .param("tokenId", tokenId)
                .query((rs, n) -> new Row(
                        rs.getString("trigger"),
                        rs.getString("estimator"),
                        rs.getInt("predicted_wait_s"),
                        rs.getInt("range_min_s"),
                        rs.getInt("range_max_s"),
                        rs.getInt("actual_wait_s")))
                .list();
        for (Row row : rows) {
            DistributionSummary.builder("baari.eta.abs_error.seconds")
                    .tag("estimator", row.estimator())
                    .tag("trigger", row.trigger())
                    .register(meters)
                    .record(Math.abs(row.actual() - row.predicted()));
            boolean hit = row.actual() >= row.min() && row.actual() <= row.max();
            Counter.builder("baari.eta.in_range")
                    .tag("hit", Boolean.toString(hit))
                    .register(meters)
                    .increment();
        }
    }
}
