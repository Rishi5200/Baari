package com.baari.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Decides what a simulated reception desk and doctor do next on a live queue (the {@code drive}
 * command, PLAN Phase 9: "simulator-driven activity" on the public demo). Pure: the board, the
 * time and the random source come in, actions go out; {@link LiveDriver} does the HTTP.
 *
 * <p>It works from the board the server returns, not from its own memory, so real visitors who
 * join by QR and staff who click in the console are served like everyone else. It only remembers
 * when it planned to start or finish a consultation, keyed by token id.
 */
final class DrivePolicy {

    /** A token on the board, reduced to what the driver needs. */
    record Tok(String id, String visitType, Instant calledAt, Instant consultStartedAt) {}

    /** Today's board; {@code dayStatus} is null when the queue has no day today. */
    record Board(String dayStatus, List<Tok> waiting, List<Tok> called, List<Tok> inConsultation) {

        static Board noDay() {
            return new Board(null, List.of(), List.of(), List.of());
        }
    }

    sealed interface Action {}

    record OpenDay() implements Action {}

    record WalkIn(String name, String visitType, String priority) implements Action {}

    record CallNext() implements Action {}

    record Start(String tokenId) implements Action {}

    record Complete(String tokenId) implements Action {}

    /** Settings of one driven queue. {@code speed} 2 halves service times and doubles arrivals. */
    record Settings(
            SimConfig.Doctor doctor,
            double arrivalsPerHour,
            double newVisitShare,
            double priorityShare,
            int maxWaiting,
            double speed) {

        Settings {
            if (arrivalsPerHour < 0 || maxWaiting < 0 || speed <= 0) {
                throw new IllegalArgumentException("arrivals and maxWaiting must be >= 0, speed > 0");
            }
        }
    }

    /** Patient walks from the waiting room to the doctor's room: 30 to 90 s. */
    static final long WALK_MIN_S = 30;

    static final long WALK_MAX_S = 90;

    /** The doctor's pause between finishing one patient and calling the next. */
    static final long GAP_S = 30;

    private static final String[] FIRST_NAMES = {
        "Aarav", "Priya", "Rohan", "Ananya", "Vikram", "Kavya", "Arjun", "Meera", "Karan", "Simran",
        "Rahul", "Pooja", "Aditya", "Neha", "Harpreet", "Gurpreet", "Sanjay", "Lakshmi", "Imran", "Fatima"
    };

    private final Settings settings;
    private final Random rnd;
    private final Map<String, Instant> plannedStart = new HashMap<>();
    private final Map<String, Instant> plannedEnd = new HashMap<>();
    private Instant lastCompleted = Instant.MIN;

    DrivePolicy(Settings settings, Random rnd) {
        this.settings = settings;
        this.rnd = rnd;
    }

    /**
     * The actions for one tick of {@code tick} length. At most one doctor action per tick, so the
     * next tick sees its effect on the board before deciding again.
     */
    List<Action> decide(Board board, Instant now, Duration tick) {
        if (board.dayStatus() == null) {
            return List.of(new OpenDay());
        }
        if (!board.dayStatus().equals("OPEN")) {
            return List.of(); // paused or closed by a person: leave it alone
        }
        forgetGone(board);
        List<Action> actions = new ArrayList<>(arrivals(board, tick));
        Action doctor = doctorAction(board, now);
        if (doctor != null) {
            actions.add(doctor);
        }
        return actions;
    }

    /** Remembers that a consultation just finished, so the doctor pauses before the next call. */
    void completed(Instant at) {
        lastCompleted = at;
    }

    private List<Action> arrivals(Board board, Duration tick) {
        double mean = settings.arrivalsPerHour() * settings.speed() * tick.toMillis() / 3_600_000.0;
        int count = poisson(mean);
        int room = Math.max(0, settings.maxWaiting() - board.waiting().size());
        List<Action> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, room); i++) {
            String visit = rnd.nextDouble() < settings.newVisitShare() ? "NEW" : "FOLLOW_UP";
            String priority = rnd.nextDouble() < settings.priorityShare() ? "PRIORITY" : "NORMAL";
            out.add(new WalkIn(name(), visit, priority));
        }
        return out;
    }

    private Action doctorAction(Board board, Instant now) {
        if (!board.inConsultation().isEmpty()) {
            Tok t = board.inConsultation().getFirst();
            Instant started = t.consultStartedAt() == null ? now : t.consultStartedAt();
            Instant end = plannedEnd.computeIfAbsent(t.id(), id -> started.plus(serviceTime(t.visitType())));
            return now.isBefore(end) ? null : new Complete(t.id());
        }
        if (!board.called().isEmpty()) {
            Tok t = board.called().getFirst();
            Instant called = t.calledAt() == null ? now : t.calledAt();
            Instant start = plannedStart.computeIfAbsent(t.id(), id -> called.plus(walk()));
            return now.isBefore(start) ? null : new Start(t.id());
        }
        if (!board.waiting().isEmpty() && !now.isBefore(lastCompleted.plus(scaled(GAP_S)))) {
            return new CallNext();
        }
        return null;
    }

    /** Lognormal around the doctor's median for the visit type (as in {@link DaySimulation}). */
    Duration serviceTime(String visitType) {
        SimConfig.Doctor d = settings.doctor();
        double median = "NEW".equals(visitType) ? d.medianNewS() : d.medianFollowUpS();
        double seconds = median * Math.exp(d.sigma() * rnd.nextGaussian()) / settings.speed();
        return Duration.ofMillis(Math.round(seconds * 1000));
    }

    private Duration walk() {
        return scaled(WALK_MIN_S + rnd.nextLong(WALK_MAX_S - WALK_MIN_S + 1));
    }

    private Duration scaled(long seconds) {
        return Duration.ofMillis(Math.round(seconds * 1000 / settings.speed()));
    }

    private void forgetGone(Board board) {
        Set<String> present = new HashSet<>();
        board.called().forEach(t -> present.add(t.id()));
        board.inConsultation().forEach(t -> present.add(t.id()));
        plannedStart.keySet().retainAll(present);
        plannedEnd.keySet().retainAll(present);
    }

    private String name() {
        return FIRST_NAMES[rnd.nextInt(FIRST_NAMES.length)] + " " + (char) ('A' + rnd.nextInt(26)) + ".";
    }

    /** Knuth's method; fine for the small means of one tick. */
    private int poisson(double mean) {
        double limit = Math.exp(-mean);
        double p = rnd.nextDouble();
        int k = 0;
        while (p > limit) {
            p *= rnd.nextDouble();
            k++;
        }
        return k;
    }
}
