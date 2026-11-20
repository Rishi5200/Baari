package com.baari.simulator;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Everything that shapes a simulated clinic (PLAN Phase 6). Times of day are minutes after
 * midnight; service times are lognormal with a median per visit type.
 */
record SimConfig(
        String clinic,
        ZoneId zone,
        List<Session> sessions,
        List<Doctor> doctors,
        double[] hourEffect,
        double fatiguePerPatient,
        double lateStartMeanMin,
        double[] arrivalsPerHour,
        double newVisitShare,
        double priorityShare,
        double emergencyShare,
        double noShowProbability,
        double qrShare) {

    record Session(int startMin, int endMin) {}

    /** Median service seconds per visit type, the lognormal spread (sigma of the log), share of arrivals. */
    record Doctor(String name, double medianNewS, double medianFollowUpS, double sigma, double arrivalShare) {}

    @SuppressWarnings("unchecked")
    static SimConfig parse(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        List<Session> sessions = new ArrayList<>();
        for (Object o : (List<Object>) root.get("sessions")) {
            Map<String, Object> m = (Map<String, Object>) o;
            sessions.add(new Session(minutes((String) m.get("start")), minutes((String) m.get("end"))));
        }
        List<Doctor> doctors = new ArrayList<>();
        for (Object o : (List<Object>) root.get("doctors")) {
            Map<String, Object> m = (Map<String, Object>) o;
            doctors.add(new Doctor(
                    (String) m.get("name"),
                    num(m, "medianNewS"),
                    num(m, "medianFollowUpS"),
                    num(m, "sigma"),
                    num(m, "arrivalShare")));
        }
        double[] hourEffect = new double[24];
        Arrays.fill(hourEffect, 1.0);
        Map<String, Object> he = (Map<String, Object>) root.get("hourEffect");
        if (he != null) {
            he.forEach((h, v) -> hourEffect[Integer.parseInt(h)] = (Double) v);
        }
        double[] rates = new double[24];
        Map<String, Object> ar = (Map<String, Object>) root.get("arrivalsPerHour");
        ar.forEach((h, v) -> rates[Integer.parseInt(h)] = (Double) v);
        SimConfig c = new SimConfig(
                (String) root.get("clinic"),
                ZoneId.of((String) root.getOrDefault("zone", "Asia/Kolkata")),
                sessions,
                doctors,
                hourEffect,
                num(root, "fatiguePerPatient"),
                num(root, "lateStartMeanMin"),
                rates,
                num(root, "newVisitShare"),
                num(root, "priorityShare"),
                num(root, "emergencyShare"),
                num(root, "noShowProbability"),
                num(root, "qrShare"));
        c.validate();
        return c;
    }

    private void validate() {
        if (sessions.isEmpty() || doctors.isEmpty()) {
            throw new IllegalArgumentException("need at least one session and one doctor");
        }
        for (Session s : sessions) {
            if (s.startMin() >= s.endMin()) {
                throw new IllegalArgumentException("session must end after it starts");
            }
        }
        double shares = doctors.stream().mapToDouble(Doctor::arrivalShare).sum();
        if (Math.abs(shares - 1.0) > 1e-6) {
            throw new IllegalArgumentException("doctor arrivalShare values must sum to 1, got " + shares);
        }
        for (double p : new double[] {newVisitShare, priorityShare, emergencyShare, noShowProbability, qrShare}) {
            if (p < 0 || p > 1) {
                throw new IllegalArgumentException("probabilities must be within 0..1");
            }
        }
        if (priorityShare + emergencyShare > 1) {
            throw new IllegalArgumentException("priorityShare + emergencyShare must be <= 1");
        }
    }

    private static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Double d)) {
            throw new IllegalArgumentException("missing or non-numeric: " + key);
        }
        return d;
    }

    private static int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }
}
