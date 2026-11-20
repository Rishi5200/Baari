package com.baari.eta;

/**
 * A wait time as a patient reads it (PLAN §8.5): never one exact minute. The words come from the
 * message files (so they can be translated); this record carries only the kind and the numbers.
 *
 * <ul>
 *   <li>{@code FEW_MINUTES}: middle of the range under 5 minutes
 *   <li>{@code RANGE}: from and to in minutes, rounded to 5 under 30 minutes and to 10 above
 *   <li>{@code OVER_TWO_HOURS}: middle of the range above 2 hours
 * </ul>
 *
 * @param fromMinutes null unless RANGE
 * @param toMinutes null unless RANGE
 */
public record EtaDisplay(Kind kind, Integer fromMinutes, Integer toMinutes) {

    public enum Kind {
        FEW_MINUTES,
        RANGE,
        OVER_TWO_HOURS
    }

    private static final double FEW_BELOW_MINUTES = 5;
    private static final double SHORT_BELOW_MINUTES = 30;
    private static final double OVER_ABOVE_MINUTES = 120;

    /** Rounds a range given in seconds. */
    public static EtaDisplay of(int minSeconds, int maxSeconds) {
        double min = minSeconds / 60.0;
        double max = maxSeconds / 60.0;
        double mid = (min + max) / 2;
        if (mid < FEW_BELOW_MINUTES) {
            return new EtaDisplay(Kind.FEW_MINUTES, null, null);
        }
        if (mid > OVER_ABOVE_MINUTES) {
            return new EtaDisplay(Kind.OVER_TWO_HOURS, null, null);
        }
        int step = mid < SHORT_BELOW_MINUTES ? 5 : 10;
        int from = Math.max(step, nearest(min, step));
        int to = nearest(max, step);
        if (to <= from) {
            to = from + step;
        }
        return new EtaDisplay(Kind.RANGE, from, to);
    }

    private static int nearest(double minutes, int step) {
        return (int) Math.round(minutes / step) * step;
    }
}
