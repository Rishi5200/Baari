package com.baari.eta;

import com.baari.queue.VisitType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Estimates from what this clinic's doctors actually did (PLAN §8.3). For each guess it looks for
 * the most specific group of past consultations that has enough samples, from most to least
 * specific: (doctor, visit type, hour), (doctor, visit type), (doctor), (clinic, visit type),
 * (clinic). Within a group only the newest {@link #MAX_PER_KEY} samples count, so the estimate
 * follows changes in how the doctor works. The mean is the plain mean of those samples and p20 and
 * p80 are their empirical quantiles (ADR 0010). If no group is big enough the {@code fallback}
 * answers.
 *
 * <p>Pure: built from a list of samples, no clock, no database.
 */
public final class RollingStatsEstimator implements ServiceTimeEstimator {

    public static final String NAME = "ROLLING";

    /** Newest samples used per group. */
    public static final int MAX_PER_KEY = 200;

    /** Shorter than this is a mis-click (started and ended at once), not a consultation. */
    public static final int MIN_SERVICE_SECONDS = 20;

    /** Longer than this is a forgotten "complete" button, not a consultation. */
    public static final int MAX_SERVICE_SECONDS = 3600;

    /** One finished consultation. */
    public record Sample(UUID doctorId, VisitType visitType, int hourOfDay, int serviceSeconds) {}

    private final List<Sample> samples;
    private final int minSamples;
    private final int droppedOutliers;
    private final ServiceTimeEstimator fallback;
    private final Map<List<Object>, Optional<Distribution>> memo = new HashMap<>();

    /**
     * @param newestFirst finished consultations of one clinic, newest first
     * @param minSamples a group needs at least this many samples to be used
     */
    public RollingStatsEstimator(List<Sample> newestFirst, int minSamples, ServiceTimeEstimator fallback) {
        if (minSamples < 1) {
            throw new IllegalArgumentException("minSamples must be >= 1");
        }
        this.minSamples = minSamples;
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        List<Sample> usable = new ArrayList<>();
        int dropped = 0;
        for (Sample sample : newestFirst) {
            if (sample.serviceSeconds() < MIN_SERVICE_SECONDS || sample.serviceSeconds() > MAX_SERVICE_SECONDS) {
                dropped++;
            } else {
                usable.add(sample);
            }
        }
        this.samples = List.copyOf(usable);
        this.droppedOutliers = dropped;
    }

    /** True if the clinic as a whole has enough samples for this estimator to be worth using. */
    public boolean hasEnoughData() {
        return samples.size() >= minSamples;
    }

    /** Samples thrown away as too short or too long. */
    public int droppedOutliers() {
        return droppedOutliers;
    }

    @Override
    public synchronized Distribution estimate(ServiceContext ctx) {
        UUID doctor = ctx.doctorId();
        VisitType visit = ctx.visitType();
        int hour = ctx.hourOfDay();
        if (doctor != null) {
            Optional<Distribution> found = group(
                    List.of("doctor-visit-hour", doctor, visit, hour),
                    s -> doctor.equals(s.doctorId()) && s.visitType() == visit && s.hourOfDay() == hour);
            if (found.isEmpty()) {
                found = group(
                        List.of("doctor-visit", doctor, visit),
                        s -> doctor.equals(s.doctorId()) && s.visitType() == visit);
            }
            if (found.isEmpty()) {
                found = group(List.of("doctor", doctor), s -> doctor.equals(s.doctorId()));
            }
            if (found.isPresent()) {
                return found.get();
            }
        }
        Optional<Distribution> clinic = group(List.of("clinic-visit", visit), s -> s.visitType() == visit);
        if (clinic.isEmpty()) {
            clinic = group(List.of("clinic"), s -> true);
        }
        return clinic.orElseGet(() -> fallback.estimate(ctx));
    }

    @Override
    public String name() {
        return NAME;
    }

    private Optional<Distribution> group(List<Object> key, Predicate<Sample> member) {
        return memo.computeIfAbsent(key, k -> {
            List<Double> times = samples.stream()
                    .filter(member)
                    .limit(MAX_PER_KEY)
                    .map(s -> (double) s.serviceSeconds())
                    .sorted()
                    .toList();
            if (times.size() < minSamples) {
                return Optional.empty();
            }
            double mean =
                    times.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            return Optional.of(new Distribution(mean, quantile(times, 0.2), quantile(times, 0.8)));
        });
    }

    /** Quantile of sorted values by linear interpolation between neighbours. */
    static double quantile(List<Double> sorted, double q) {
        double position = q * (sorted.size() - 1);
        int below = (int) Math.floor(position);
        int above = (int) Math.ceil(position);
        return sorted.get(below) + (sorted.get(above) - sorted.get(below)) * (position - below);
    }
}
