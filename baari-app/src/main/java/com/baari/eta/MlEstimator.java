package com.baari.eta;

import java.util.Objects;

/**
 * Consultation lengths from the clinic's champion ML model (PLAN §8.6). If the model cannot answer
 * for a context (none of its features was seen in training, or the output is not a usable number),
 * the fallback (Rolling or Default) answers instead, so a patient always gets an estimate.
 */
public final class MlEstimator implements ServiceTimeEstimator {

    public static final String PREFIX = "ML:";

    private final ServiceTimeModel model;
    private final int version;
    private final ServiceTimeEstimator fallback;

    public MlEstimator(ServiceTimeModel model, int version, ServiceTimeEstimator fallback) {
        this.model = Objects.requireNonNull(model, "model");
        this.version = version;
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public Distribution estimate(ServiceContext context) {
        try {
            Distribution d = model.predict(context);
            if (Double.isFinite(d.meanS()) && Double.isFinite(d.p80S()) && d.meanS() >= 1) {
                return d;
            }
        } catch (RuntimeException e) {
            // unknown features or a numeric problem: fall through to the fallback
        }
        return fallback.estimate(context);
    }

    /** {@code ML:<algorithm>:v<version>}, stored with every logged prediction. */
    @Override
    public String name() {
        return PREFIX + model.algorithm() + ":v" + version;
    }
}
