package com.baari.eta;

import com.baari.queue.HistoricalToken;
import com.baari.queue.NextTokenPolicy;
import com.baari.queue.Priority;
import com.baari.queue.QueueSnapshot;
import com.baari.queue.TokenStatus;
import com.baari.queue.TokenView;
import com.baari.queue.VisitType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Replays a past queue day through the real {@link QueueProjector} and scores each way of predicting
 * against the waits that really followed (PLAN §8.6 step 4, Phase 6 backtest). At every moment a
 * patient joined or was called, the queue is rebuilt as it was then (who waits, who is in the room,
 * each doctor's session so far) and every variant projects it. Predictions are logged at the
 * production triggers: JOIN, and the first time a patient is within 10, 5, 3 and 1 places.
 *
 * <p>Simplifications: recalled no-shows keep their original place, and a no-show keeps the doctor
 * waiting for {@link #NO_SHOW_GRACE_S} seconds. Pure: no clock, database or Spring.
 */
public final class WaitReplay {

    public static final List<String> TRIGGERS =
            List.of("JOIN", "POSITION_10", "POSITION_5", "POSITION_3", "POSITION_1");
    private static final int[] MILESTONES = {10, 5, 3, 1};
    static final long NO_SHOW_GRACE_S = 180;

    private WaitReplay() {}

    /** One way of predicting: an estimator and the range widening factor it runs with. */
    public record Variant(String name, ServiceTimeEstimator estimator, double calibration) {}

    /** One scored prediction, in seconds. */
    public record Observation(String variant, String trigger, int predictedS, int minS, int maxS, int actualS) {
        public int errorS() {
            return predictedS - actualS;
        }

        public boolean covered() {
            return actualS >= minS && actualS <= maxS;
        }
    }

    /** Summary of a set of observations, in minutes. */
    public record Stats(int n, double maeMin, double medianMin, double p90Min, double biasMin, double coverage) {}

    public record Settings(ZoneId zone, int transitionGapSeconds, NextTokenPolicy.Settings policy) {}

    /** Replays every queue day in {@code tokens}; days are independent of each other. */
    public static List<Observation> replay(List<HistoricalToken> tokens, List<Variant> variants, Settings settings) {
        Map<UUID, List<HistoricalToken>> byDay = tokens.stream()
                .collect(Collectors.groupingBy(HistoricalToken::queueDayId, LinkedHashMap::new, Collectors.toList()));
        List<Observation> out = new ArrayList<>();
        for (List<HistoricalToken> day : byDay.values()) {
            out.addAll(replayDay(day, variants, settings));
        }
        return out;
    }

    /** Replays one queue day. */
    static List<Observation> replayDay(List<HistoricalToken> rows, List<Variant> variants, Settings settings) {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("need at least one variant");
        }
        // the doctors who worked this day, in a fixed order so the projection is deterministic
        List<UUID> doctors = rows.stream()
                .map(HistoricalToken::doctorId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (doctors.isEmpty()) {
            return List.of(); // nobody was ever called: nothing to compare with
        }
        Map<UUID, List<Instant>> startsByDoctor = new HashMap<>();
        for (UUID d : doctors) {
            startsByDoctor.put(
                    d,
                    rows.stream()
                            .filter(r -> d.equals(r.doctorId()) && r.consultStartedAt() != null)
                            .map(HistoricalToken::consultStartedAt)
                            .sorted()
                            .toList());
        }
        List<HistoricalToken> byCall = rows.stream()
                .filter(r -> r.calledAt() != null)
                .sorted(Comparator.comparing(HistoricalToken::calledAt))
                .toList();
        TreeSet<Instant> moments = new TreeSet<>();
        rows.forEach(r -> {
            moments.add(r.joinedAt());
            if (r.calledAt() != null) {
                moments.add(r.calledAt());
            }
        });
        Map<UUID, HistoricalToken> byId =
                rows.stream().collect(Collectors.toMap(HistoricalToken::id, Function.identity()));
        Map<UUID, Integer> joinPosition = new HashMap<>();
        Map<UUID, Set<String>> logged = new HashMap<>();

        List<Observation> out = new ArrayList<>();
        for (Instant now : moments) {
            List<HistoricalToken> waiting = rows.stream()
                    .filter(r -> r.waitingAt(now) || calledOnArrival(r, now))
                    .toList();
            if (waiting.isEmpty()) {
                continue;
            }
            List<QueueProjector.Server> servers = new ArrayList<>();
            for (UUID d : doctors) {
                QueueProjector.Consult current = rows.stream()
                        .filter(r -> d.equals(r.doctorId()) && inRoom(r, now))
                        .findFirst()
                        .map(r -> new QueueProjector.Consult(r.id(), r.visitType(), r.consultStartedAt()))
                        .orElse(null);
                Sessions.SoFar session = Sessions.soFar(startsByDoctor.get(d), now);
                if (current != null && session.started() == 0) {
                    session = Sessions.SoFar.NONE.next(current.startedAt());
                }
                servers.add(new QueueProjector.Server(d, current, session));
            }
            List<QueueProjector.Called> called = rows.stream()
                    .filter(r -> r.calledAt() != null
                            && !r.calledAt().isAfter(now)
                            && !calledOnArrival(r, now)
                            && onItsWay(r, now))
                    .map(r -> new QueueProjector.Called(r.id(), r.visitType(), r.calledAt(), r.doctorId()))
                    .toList();
            List<TokenView> views = waiting.stream()
                    .map(r -> new TokenView(
                            r.id(),
                            r.number(),
                            r.priority(),
                            TokenStatus.WAITING,
                            TokenView.initialSortKey(r.number()),
                            r.joinedAt()))
                    .toList();
            Map<UUID, VisitType> visits =
                    waiting.stream().collect(Collectors.toMap(HistoricalToken::id, HistoricalToken::visitType));
            QueueProjector.Input input = new QueueProjector.Input(
                    now,
                    settings.zone(),
                    servers,
                    called,
                    new QueueSnapshot(views),
                    visits,
                    policyStateAt(byCall, now),
                    settings.policy());

            Map<String, Map<UUID, QueueProjector.Projection>> projections = new LinkedHashMap<>();
            for (Variant v : variants) {
                // no no-show allowance: a no-show still costs the doctor a few minutes (ADR 0012)
                QueueProjector.Params params =
                        new QueueProjector.Params(settings.transitionGapSeconds(), 0.0, v.calibration());
                Map<UUID, QueueProjector.Projection> byToken = new HashMap<>();
                QueueProjector.project(input, v.estimator(), params).forEach(p -> byToken.put(p.tokenId(), p));
                projections.put(v.name(), byToken);
            }

            // positions do not depend on the estimator, so any variant says who crossed which milestone
            for (QueueProjector.Projection p :
                    projections.get(variants.get(0).name()).values()) {
                HistoricalToken row = byId.get(p.tokenId());
                Set<String> done = logged.computeIfAbsent(p.tokenId(), id -> new HashSet<>());
                List<String> fired = new ArrayList<>();
                if (!done.contains("JOIN")) {
                    joinPosition.put(p.tokenId(), p.position());
                    fired.add("JOIN");
                }
                for (int milestone : MILESTONES) {
                    String trigger = "POSITION_" + milestone;
                    if (p.position() <= milestone
                            && joinPosition.get(p.tokenId()) > milestone
                            && !done.contains(trigger)) {
                        fired.add(trigger);
                    }
                }
                for (String trigger : fired) {
                    done.add(trigger);
                    if (row.consultStartedAt() == null) {
                        continue; // never seen (no-show, cancelled): no actual wait to compare with
                    }
                    int actual = (int) (row.consultStartedAt().getEpochSecond() - now.getEpochSecond());
                    for (Map.Entry<String, Map<UUID, QueueProjector.Projection>> e : projections.entrySet()) {
                        QueueProjector.Projection mine = e.getValue().get(p.tokenId());
                        out.add(new Observation(
                                e.getKey(),
                                trigger,
                                mine.waitSeconds(),
                                mine.rangeMinSeconds(),
                                mine.rangeMaxSeconds(),
                                actual));
                    }
                }
            }
        }
        return out;
    }

    /**
     * A patient who walks in to an idle doctor is called at the very second they join. Production
     * still logs their JOIN prediction (they exist as WAITING for that instant), so the replay does too.
     */
    private static boolean calledOnArrival(HistoricalToken r, Instant now) {
        return r.joinedAt().equals(r.calledAt()) && r.joinedAt().equals(now);
    }

    private static boolean inRoom(HistoricalToken r, Instant now) {
        return r.consultStartedAt() != null
                && !r.consultStartedAt().isAfter(now)
                && (r.completedAt() == null || r.completedAt().isAfter(now));
    }

    /** Called, and either not yet in the room or (for a no-show) still within the doctor's wait. */
    private static boolean onItsWay(HistoricalToken r, Instant now) {
        if (r.consultStartedAt() != null) {
            return r.consultStartedAt().isAfter(now);
        }
        return r.status() == TokenStatus.NO_SHOW && now.isBefore(r.calledAt().plusSeconds(NO_SHOW_GRACE_S));
    }

    /** The policy's memory after every call up to {@code now}: same update rules as {@link NextTokenPolicy}. */
    private static NextTokenPolicy.State policyStateAt(List<HistoricalToken> byCall, Instant now) {
        int counter = 0;
        for (HistoricalToken r : byCall) {
            if (r.calledAt().isAfter(now)) {
                break;
            }
            if (r.priority() == Priority.PRIORITY) {
                counter++;
            } else if (r.priority() == Priority.NORMAL) {
                counter = 0;
            }
        }
        return new NextTokenPolicy.State(counter);
    }

    public static Stats stats(List<Observation> obs) {
        if (obs.isEmpty()) {
            return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        List<Double> abs =
                obs.stream().map(o -> Math.abs(o.errorS()) / 60.0).sorted().toList();
        double mae = abs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double bias = obs.stream().mapToDouble(o -> o.errorS() / 60.0).average().orElseThrow();
        double covered = obs.stream().filter(Observation::covered).count() / (double) obs.size();
        return new Stats(
                obs.size(),
                mae,
                RollingStatsEstimator.quantile(abs, 0.5),
                RollingStatsEstimator.quantile(abs, 0.9),
                bias,
                covered);
    }
}
