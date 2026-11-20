package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;

import com.baari.eta.EtaDisplay.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Display rounding (PLAN §8.5). Arguments are seconds; minutes x 60 for readability. */
@Tag("unit")
class EtaDisplayTest {

    private static EtaDisplay of(double minMinutes, double maxMinutes) {
        return EtaDisplay.of((int) (minMinutes * 60), (int) (maxMinutes * 60));
    }

    private static EtaDisplay range(int from, int to) {
        return new EtaDisplay(Kind.RANGE, from, to);
    }

    @Test
    void underFiveMinutesIsAFewMinutes() {
        assertThat(of(0, 0)).isEqualTo(new EtaDisplay(Kind.FEW_MINUTES, null, null));
        assertThat(of(0, 9.9)).isEqualTo(new EtaDisplay(Kind.FEW_MINUTES, null, null));
    }

    @Test
    void fiveMinutesIsTheFirstRange() {
        // middle exactly 5 minutes: no longer "a few"
        assertThat(of(4, 6)).isEqualTo(range(5, 5 + 5));
    }

    @Test
    void underThirtyMinutesRoundsToTheNearestFive() {
        assertThat(of(12, 18)).isEqualTo(range(10, 20));
        assertThat(of(14, 21)).isEqualTo(range(15, 20));
    }

    @Test
    void thirtyMinutesAndMoreRoundsToTheNearestTen() {
        assertThat(of(28, 34))
                .isEqualTo(range(30, 30 + 10)); // middle 31: ten-minute steps; 28 -> 30, 34 -> 30, widened
        assertThat(of(44, 58)).isEqualTo(range(40, 60));
    }

    @Test
    void theLowEndIsNeverBelowOneStep() {
        // middle 6 minutes, low end 1 minute would round to 0
        assertThat(of(1, 11)).isEqualTo(range(5, 10));
    }

    @Test
    void aRangeThatRoundsToOnePointIsWidenedByOneStep() {
        assertThat(of(15, 16)).isEqualTo(range(15, 20));
    }

    @Test
    void exactlyTwoHoursIsStillARange() {
        assertThat(of(110, 130)).isEqualTo(range(110, 130));
    }

    @Test
    void moreThanTwoHoursIsOverTwoHours() {
        assertThat(of(121, 130)).isEqualTo(new EtaDisplay(Kind.OVER_TWO_HOURS, null, null));
        assertThat(of(180, 240)).isEqualTo(new EtaDisplay(Kind.OVER_TWO_HOURS, null, null));
    }
}
