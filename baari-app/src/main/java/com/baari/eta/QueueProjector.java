package com.baari.eta;

import com.baari.queue.NextTokenPolicy;
import com.baari.queue.QueueSnapshot;
import com.baari.queue.TokenView;
import com.baari.queue.VisitType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Predicts when each waiting patient's consultation will start (PLAN §8.4, list scheduling). Each
 * doctor is a "server" with a time when they become free. Patients are taken in exactly the order
 * {@link NextTokenPolicy#projectOrder} gives (the order they will really be called in) and each
 * goes to the server that frees up first.
 *
 * <p>The range around a start time comes from adding up the uncertainty of every consultation
 * ahead of the patient on the same server, as if consultation lengths were independent and normal.
 *
 * <p>Pure: {@code now} is an input, nothing is read from a clock or database.
 */
public final class QueueProjector {

    /** z for the 80th percentile of a normal distribution: ±0.8416 sds cover the middle 60%. */
    static final double Z_P80 = 0.8416;

    /** A consultation that runs over its estimate is assumed to need at least this share of it still. */
    static final double MIN_REMAINING_SHARE = 0.25;

    private QueueProjector() {}

    /** A consultation in progress. */
    public record Consult(UUID tokenId, VisitType visitType, Instant startedAt) {}

    /** One doctor (room) serving this queue today; {@code current} is null when they are free. */
    public record Server(UUID doctorId, Consult current) {}

    /** A token that has been called but whose consultation has not started yet. */
    public record Called(UUID tokenId, VisitType visitType, Instant calledAt, UUID doctorId) {}

    /**
     * @param snapshot the day's tokens; only WAITING ones are projected
     * @param visitTypes visit type of every waiting token
     * @param servers the doctors serving now; with none, nothing can be predicted
     */
    public record Input(
            Instant now,
            ZoneId zone,
            List<Server> servers,
            List<Called> called,
            QueueSnapshot snapshot,
            Map<UUID, VisitType> visitTypes,
            NextTokenPolicy.State policyState,
            NextTokenPolicy.Settings policySettings) {

        public Input {
            servers = List.copyOf(servers);
            called = List.copyOf(called);
            visitTypes = Map.copyOf(visitTypes);
        }
    }

    /**
     * @param transitionGapSeconds time between one consultation ending and the next starting
     * @param noShowRate share of patients who do not turn up; they take no consultation time
     * @param calibration widens (above 1) or narrows the range; 1 until calibration exists (PLAN §8.5)
     */
    public record Params(int transitionGapSeconds, double noShowRate, double calibration) {
        public Params {
            if (transitionGapSeconds < 0) {
                throw new IllegalArgumentException("transitionGapSeconds must be >= 0");
            }
            if (noShowRate < 0 || noShowRate >= 1) {
                throw new IllegalArgumentException("noShowRate must be in [0, 1)");
            }
            if (calibration <= 0) {
                throw new IllegalArgumentException("calibration must be > 0");
            }
        }
    }

    /**
     * @param position 1 = called next
     * @param waitSeconds expected seconds from now until the consultation starts
     * @param rangeMinSeconds p20 end of the range, from now, never below 0
     * @param rangeMaxSeconds p80 end of the range, from now
     */
    public record Projection(
            UUID tokenId,
            int position,
            Instant expectedStartAt,
            int waitSeconds,
            int rangeMinSeconds,
            int rangeMaxSeconds) {}

    /** One doctor's schedule while the projection runs: when free, and how uncertain that is. */
    private static final class Lane {
        final UUID doctorId;
        double freeAtS;
        double varianceS2;

        Lane(UUID doctorId) {
            this.doctorId = doctorId;
        }
    }

    /** Projections for the WAITING tokens, in call order. Empty if no doctor is serving. */
    public static List<Projection> project(Input in, ServiceTimeEstimator estimator, Params params) {
        if (in.servers().isEmpty()) {
            return List.of();
        }
        List<Lane> lanes = new ArrayList<>();
        for (Server server : in.servers()) {
            Lane lane = new Lane(server.doctorId());
            Consult current = server.current();
            if (current != null) {
                Distribution d = estimator.estimate(
                        context(server.doctorId(), current.visitType(), in.zone(), current.startedAt()));
                double elapsed = Math.max(0, secondsBetween(current.startedAt(), in.now()));
                lane.freeAtS = Math.max(d.meanS() - elapsed, MIN_REMAINING_SHARE * d.meanS());
                lane.varianceS2 = d.sdS() * d.sdS();
            }
            lanes.add(lane);
        }

        // Called tokens go first, in the order they were called; they are already on their way in.
        List<Called> called = in.called().stream()
                .sorted(Comparator.comparing(Called::calledAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (Called c : called) {
            Lane lane = laneFor(lanes, c.doctorId());
            occupy(lane, estimator, in, c.visitType(), params);
        }

        List<Projection> projections = new ArrayList<>();
        List<TokenView> order = NextTokenPolicy.projectOrder(in.snapshot(), in.policyState(), in.policySettings());
        for (int i = 0; i < order.size(); i++) {
            TokenView token = order.get(i);
            Lane lane = earliest(lanes);
            double start = lane.freeAtS;
            double half = Z_P80 * Math.sqrt(lane.varianceS2) * params.calibration();
            projections.add(new Projection(
                    token.id(),
                    i + 1,
                    in.now().plusMillis(Math.round(start * 1000)),
                    (int) Math.round(start),
                    (int) Math.round(Math.max(0, start - half)),
                    (int) Math.round(start + half)));
            VisitType visit = in.visitTypes().getOrDefault(token.id(), VisitType.NEW);
            occupy(lane, estimator, in, visit, params);
        }
        return List.copyOf(projections);
    }

    /** Books the lane for one more consultation: the gap, then the expected time if they turn up. */
    private static void occupy(Lane lane, ServiceTimeEstimator estimator, Input in, VisitType visit, Params params) {
        Instant start = in.now().plusMillis(Math.round(lane.freeAtS * 1000));
        Distribution d = estimator.estimate(context(lane.doctorId, visit, in.zone(), start));
        lane.freeAtS += params.transitionGapSeconds() + (1 - params.noShowRate()) * d.meanS();
        lane.varianceS2 += d.sdS() * d.sdS();
    }

    /** The lane of the doctor the token was called to, else the one that frees up first. */
    private static Lane laneFor(List<Lane> lanes, UUID doctorId) {
        return lanes.stream()
                .filter(l -> Objects.equals(l.doctorId, doctorId))
                .findFirst()
                .orElseGet(() -> earliest(lanes));
    }

    /** First lane wins a tie, so the result does not depend on hash order. */
    private static Lane earliest(List<Lane> lanes) {
        Lane best = lanes.get(0);
        for (Lane lane : lanes) {
            if (lane.freeAtS < best.freeAtS) {
                best = lane;
            }
        }
        return best;
    }

    private static ServiceContext context(UUID doctorId, VisitType visit, ZoneId zone, Instant at) {
        return new ServiceContext(doctorId, visit, at.atZone(zone).getHour());
    }

    private static double secondsBetween(Instant from, Instant to) {
        return (to.toEpochMilli() - from.toEpochMilli()) / 1000.0;
    }
}
