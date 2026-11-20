package com.baari.eta;

import java.util.Objects;

/**
 * Keeps the range a patient sees steady (PLAN §8.5). A new prediction replaces the shown one only
 * if the patient's position changed, or the middle of the range moved by at least 2 minutes and at
 * least 10%. Even then the upper end may rise by at most 5 minutes at a time, unless the position
 * got worse (someone with priority jumped ahead), so a patient is never whipsawed.
 *
 * <p>Pure: the caller remembers the previously shown range.
 */
public final class EtaSmoother {

    private EtaSmoother() {}

    /** A range as shown, in seconds from the moment it was computed, and the position it was for. */
    public record Shown(int minSeconds, int maxSeconds, int position) {
        public Shown {
            if (minSeconds < 0 || maxSeconds < minSeconds) {
                throw new IllegalArgumentException("need 0 <= minSeconds <= maxSeconds");
            }
        }

        double midSeconds() {
            return (minSeconds + maxSeconds) / 2.0;
        }
    }

    public record Settings(int minChangeSeconds, double minChangeFraction, int maxUpperIncreaseSeconds) {
        public static final Settings DEFAULT = new Settings(120, 0.10, 300);
    }

    /**
     * @param previous what the patient sees now; null if nothing was shown yet
     * @param candidate the newly computed range
     * @return the range to show: {@code previous} unchanged, or the candidate (upper end capped)
     */
    public static Shown smooth(Shown previous, Shown candidate, Settings settings) {
        Objects.requireNonNull(candidate, "candidate");
        if (previous == null) {
            return candidate;
        }
        boolean positionChanged = candidate.position() != previous.position();
        boolean positionWorse = candidate.position() > previous.position();
        if (!positionChanged) {
            double moved = Math.abs(candidate.midSeconds() - previous.midSeconds());
            boolean bigEnough = moved >= settings.minChangeSeconds()
                    && moved >= settings.minChangeFraction() * previous.midSeconds();
            if (!bigEnough) {
                return previous;
            }
        }
        if (positionWorse) {
            return candidate;
        }
        int cap = previous.maxSeconds() + settings.maxUpperIncreaseSeconds();
        int max = Math.min(candidate.maxSeconds(), cap);
        int min = Math.min(candidate.minSeconds(), max);
        return new Shown(min, max, candidate.position());
    }
}
