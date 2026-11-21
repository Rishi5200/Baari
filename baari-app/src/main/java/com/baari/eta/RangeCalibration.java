package com.baari.eta;

/**
 * The nightly widening factor for ETA ranges (PLAN §8.5). The projector's p20 to p80 range assumes
 * consultation lengths are independent and normal; real queues are less tidy, so the range ends up
 * too narrow. Each night the share of recent predictions whose actual wait fell inside the shown
 * range is compared with the 60 to 70% target, and the factor moves one small step toward it.
 * Small steps keep one odd day from swinging the ranges patients see.
 *
 * <p>Pure: the caller supplies the counts and stores the result.
 */
public final class RangeCalibration {

    /** Factor before any calibration has run: the range exactly as the projector computes it. */
    public static final double START = 1.0;

    public static final double MIN = 1.0;
    public static final double MAX = 2.5;
    public static final double STEP = 0.1;
    public static final double TARGET_LOW = 0.60;
    public static final double TARGET_HIGH = 0.70;

    /** Days of predictions the coverage is measured over. */
    public static final int WINDOW_DAYS = 14;

    /** Fewer scored predictions than this say too little; the factor is left alone. */
    public static final int MIN_PREDICTIONS = 50;

    private RangeCalibration() {}

    /**
     * @param factor today's factor
     * @param covered predictions whose actual wait was inside the shown range
     * @param total predictions with a known actual wait
     * @return tomorrow's factor
     */
    public static double adjust(double factor, long covered, long total) {
        if (covered < 0 || total < 0 || covered > total) {
            throw new IllegalArgumentException("need 0 <= covered <= total");
        }
        if (total < MIN_PREDICTIONS) {
            return clamp(factor);
        }
        double coverage = covered / (double) total;
        double next = factor;
        if (coverage < TARGET_LOW) {
            next = factor + STEP;
        } else if (coverage > TARGET_HIGH) {
            next = factor - STEP;
        }
        // keep one decimal (the column is numeric(3,1)) so repeated steps do not drift:
        // 1.0 + 0.1 + 0.1 + 0.1 is 1.3000000000000003 in floating point
        return clamp(Math.round(next * 10) / 10.0);
    }

    private static double clamp(double factor) {
        return Math.max(MIN, Math.min(MAX, factor));
    }
}
