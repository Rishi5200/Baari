package com.baari.eta;

/**
 * How long one consultation is expected to take, in seconds: the mean and the 20th and 80th
 * percentiles (PLAN §8.2). The p20 to p80 span is what the range shown to patients is built from.
 */
public record Distribution(double meanS, double p20S, double p80S) {

    /** Width of the p20 to p80 span of a normal distribution, in standard deviations (2 × 0.8416). */
    static final double P20_P80_IN_SDS = 1.683;

    public Distribution {
        if (meanS <= 0) {
            throw new IllegalArgumentException("meanS must be > 0");
        }
        if (p20S < 0 || p20S > p80S) {
            throw new IllegalArgumentException("need 0 <= p20S <= p80S");
        }
    }

    /** Standard deviation, estimated from the p20 to p80 span as if the times were normal. */
    public double sdS() {
        return (p80S - p20S) / P20_P80_IN_SDS;
    }
}
