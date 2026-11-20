package com.baari.simulator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Discrete-event simulation of one clinic day (PLAN Phase 6). Every doctor has their own line, and
 * every (doctor, session) pair is simulated as its own run of events kept in a priority queue ordered
 * by time (ties by insertion order, so one seed always gives the same day). The next patient follows
 * the real queue's rules: emergency first, then at most {@value #MAX_PRIORITY_IN_ROW} priority
 * patients in a row, otherwise first come first served.
 */
final class DaySimulation {

    static final int MAX_PRIORITY_IN_ROW = 2;
    private static final long NO_SHOW_GRACE_S = 180;
    private static final long TRANSITION_GAP_S = 30;
    /** A few patients are already waiting when the doctor starts. */
    private static final long EARLY_ARRIVALS_S = 20 * 60;
    /** Arrivals stop this long before a session ends, as at a real clinic. */
    private static final long LAST_ARRIVAL_BEFORE_END_S = 15 * 60;

    private enum Kind {
        ARRIVAL,
        DOCTOR_READY,
        DOCTOR_FREE
    }

    private static final class Patient {
        final String visitType;
        final String priority;
        final String source;
        final boolean noShow;
        final double z; // standard normal draw for the service time, taken at creation
        final long joinedAt;
        int number;

        Patient(String visitType, String priority, String source, boolean noShow, double z, long joinedAt) {
            this.visitType = visitType;
            this.priority = priority;
            this.source = source;
            this.noShow = noShow;
            this.z = z;
            this.joinedAt = joinedAt;
        }
    }

    private record Event(long at, long order, Kind kind, Patient patient) {}

    private final SimConfig cfg;
    private final Random rnd;
    private final List<SimToken> done = new ArrayList<>();

    // state of the run in progress
    private final PriorityQueue<Event> events =
            new PriorityQueue<>(Comparator.comparingLong(Event::at).thenComparingLong(Event::order));
    private final List<Patient> line = new ArrayList<>();
    private long order;
    private boolean ready;
    private boolean busy;
    private int served;
    private int priorityInRow;
    private int nextNumber;
    private SimConfig.Doctor doctor;

    private DaySimulation(SimConfig cfg, long seed) {
        this.cfg = cfg;
        this.rnd = new Random(seed);
    }

    /** One day for the whole clinic; the same config and seed always give the same list. */
    static List<SimToken> run(SimConfig cfg, long seed) {
        DaySimulation sim = new DaySimulation(cfg, seed);
        for (SimConfig.Doctor d : cfg.doctors()) {
            sim.nextNumber = 0;
            for (SimConfig.Session s : cfg.sessions()) {
                sim.runSession(d, s);
            }
        }
        return sim.done;
    }

    private void runSession(SimConfig.Doctor d, SimConfig.Session s) {
        doctor = d;
        events.clear();
        line.clear();
        ready = false;
        busy = false;
        served = 0;
        priorityInRow = 0;

        long start = s.startMin() * 60L;
        long end = s.endMin() * 60L;
        long late = (long) (-Math.log(1 - rnd.nextDouble()) * cfg.lateStartMeanMin() * 60);
        add(start + late, Kind.DOCTOR_READY, null);
        scheduleArrivals(d, start - EARLY_ARRIVALS_S, end - LAST_ARRIVAL_BEFORE_END_S);

        while (!events.isEmpty()) {
            Event e = events.poll();
            switch (e.kind()) {
                case ARRIVAL -> {
                    e.patient().number = ++nextNumber;
                    line.add(e.patient());
                    tryCall(e.at());
                }
                case DOCTOR_READY -> {
                    ready = true;
                    tryCall(e.at());
                }
                case DOCTOR_FREE -> {
                    busy = false;
                    tryCall(e.at());
                }
            }
        }
    }

    /** Non-homogeneous Poisson arrivals: exponential gaps at the rate of the current hour. */
    private void scheduleArrivals(SimConfig.Doctor d, long from, long stop) {
        long t = from;
        while (t < stop) {
            int hour = (int) (Math.max(t, 0) / 3600) % 24;
            double perSecond = cfg.arrivalsPerHour()[hour] * d.arrivalShare() / 3600.0;
            if (perSecond <= 0) {
                t = (t / 3600 + 1) * 3600; // nobody arrives this hour; skip to the next one
                continue;
            }
            t += (long) Math.ceil(-Math.log(1 - rnd.nextDouble()) / perSecond);
            if (t < stop) {
                add(t, Kind.ARRIVAL, newPatient(t));
            }
        }
    }

    private Patient newPatient(long at) {
        String visit = rnd.nextDouble() < cfg.newVisitShare() ? "NEW" : "FOLLOW_UP";
        double p = rnd.nextDouble();
        String priority = p < cfg.emergencyShare()
                ? "EMERGENCY"
                : p < cfg.emergencyShare() + cfg.priorityShare() ? "PRIORITY" : "NORMAL";
        String source = rnd.nextDouble() < cfg.qrShare() ? "QR" : "RECEPTION";
        boolean noShow = rnd.nextDouble() < cfg.noShowProbability();
        double z = rnd.nextGaussian();
        return new Patient(visit, priority, source, noShow, z, at);
    }

    private void tryCall(long at) {
        if (busy || !ready || line.isEmpty()) {
            return;
        }
        Patient p = pickNext();
        line.remove(p);
        busy = true;
        if (p.noShow) {
            done.add(token(p, "NO_SHOW", at, -1, -1));
            add(at + NO_SHOW_GRACE_S, Kind.DOCTOR_FREE, null);
            return;
        }
        long startAt = at + TRANSITION_GAP_S;
        long finishAt = startAt + serviceSeconds(p, startAt);
        served++;
        done.add(token(p, "COMPLETED", at, startAt, finishAt));
        add(finishAt, Kind.DOCTOR_FREE, null);
    }

    private SimToken token(Patient p, String status, long calledAt, long startedAt, long completedAt) {
        return new SimToken(
                doctor.name(),
                p.number,
                p.visitType,
                p.priority,
                p.source,
                status,
                p.joinedAt,
                calledAt,
                startedAt,
                completedAt);
    }

    private Patient pickNext() {
        for (Patient p : line) {
            if (p.priority.equals("EMERGENCY")) {
                return p; // an emergency leaves the priority counter alone, as NextTokenPolicy does
            }
        }
        Patient firstPriority = null;
        Patient firstNormal = null;
        for (Patient p : line) {
            if (firstPriority == null && p.priority.equals("PRIORITY")) {
                firstPriority = p;
            }
            if (firstNormal == null && p.priority.equals("NORMAL")) {
                firstNormal = p;
            }
        }
        boolean priorityTurn = firstNormal == null || priorityInRow < MAX_PRIORITY_IN_ROW;
        Patient chosen = firstPriority != null && priorityTurn ? firstPriority : firstNormal;
        priorityInRow = chosen.priority.equals("PRIORITY") ? priorityInRow + 1 : 0;
        return chosen;
    }

    /** Lognormal around the median, scaled by the hour of day and by how tired the doctor is. */
    private long serviceSeconds(Patient p, long startAt) {
        double median = p.visitType.equals("NEW") ? doctor.medianNewS() : doctor.medianFollowUpS();
        int hour = (int) (startAt / 3600) % 24;
        double fatigue = 1 + cfg.fatiguePerPatient() * served;
        double seconds = median * cfg.hourEffect()[hour] * fatigue * Math.exp(doctor.sigma() * p.z);
        return Math.max(60, Math.round(seconds));
    }

    private void add(long at, Kind kind, Patient patient) {
        events.add(new Event(at, order++, kind, patient));
    }
}
