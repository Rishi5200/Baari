package com.baari.eta;

/**
 * The cold-start guess: every consultation takes the clinic's default length. The p20 and p80 are
 * 0.6 and 1.5 times the mean, a deliberately wide spread because nothing has been measured yet.
 */
public final class DefaultEstimator implements ServiceTimeEstimator {

    public static final String NAME = "DEFAULT";

    private final Distribution distribution;

    public DefaultEstimator(int defaultServiceSeconds) {
        if (defaultServiceSeconds <= 0) {
            throw new IllegalArgumentException("defaultServiceSeconds must be > 0");
        }
        this.distribution =
                new Distribution(defaultServiceSeconds, defaultServiceSeconds * 0.6, defaultServiceSeconds * 1.5);
    }

    @Override
    public Distribution estimate(ServiceContext context) {
        return distribution;
    }

    @Override
    public String name() {
        return NAME;
    }
}
