package com.baari.eta;

import java.util.Map;
import java.util.TreeMap;

/**
 * The ML model's view of a {@link ServiceContext}: named features with numeric values (PLAN §8.6).
 * The same method serves training and prediction, so the two can never disagree about a feature.
 * Categories are one-hot ({@code doctor=<id>}, {@code hour=10}, ...); counts are scaled to roughly
 * 0..5 so the linear model's gradient steps behave, and capped so one odd day cannot dominate.
 *
 * <p>Pure.
 */
public final class ServiceFeatures {

    private ServiceFeatures() {}

    /** Feature name to value, sorted by name (Tribuo wants a stable order). */
    public static Map<String, Double> of(ServiceContext ctx) {
        Map<String, Double> f = new TreeMap<>();
        if (ctx.doctorId() != null) {
            f.put("doctor=" + ctx.doctorId(), 1.0);
        }
        f.put("visit=" + ctx.visitType(), 1.0);
        f.put("priority=" + ctx.priority(), 1.0);
        f.put("hour=" + ctx.hourOfDay(), 1.0);
        f.put("dow=" + ctx.dayOfWeek(), 1.0);
        f.put("position", Math.min(ctx.positionInSession(), 60) / 10.0);
        f.put("hoursIntoSession", Math.min(ctx.minutesIntoSession(), 600) / 60.0);
        f.put("queueLength", Math.min(ctx.queueLength(), 50) / 10.0);
        f.put("firstOfSession", ctx.positionInSession() == 1 ? 1.0 : 0.0);
        f.put("bias", 1.0);
        return f;
    }
}
