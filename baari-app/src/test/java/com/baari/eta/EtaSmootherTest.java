package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.baari.eta.EtaSmoother.Settings;
import com.baari.eta.EtaSmoother.Shown;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** One test per hysteresis rule (PLAN §8.5). Times are in minutes x 60 to keep the numbers readable. */
@Tag("unit")
class EtaSmootherTest {

    private static final Settings S = Settings.DEFAULT;

    private static Shown shown(int minMinutes, int maxMinutes, int position) {
        return new Shown(minMinutes * 60, maxMinutes * 60, position);
    }

    @Test
    void theFirstPredictionIsShownAsIs() {
        Shown first = shown(10, 20, 4);

        assertThat(EtaSmoother.smooth(null, first, S)).isEqualTo(first);
    }

    @Test
    void aSmallMoveOfTheMiddleKeepsTheOldRange() {
        Shown old = shown(20, 30, 4); // middle 25 min

        // middle 24 min: moved 1 min, under the 2 minute rule
        assertThat(EtaSmoother.smooth(old, shown(19, 29, 4), S)).isEqualTo(old);
    }

    @Test
    void aMoveOfTwoMinutesIsStillIgnoredIfUnderTenPercent() {
        Shown old = shown(50, 70, 8); // middle 60 min, 10% is 6 min

        // middle 57 min: moved 3 min (over 2) but only 5%
        assertThat(EtaSmoother.smooth(old, shown(47, 67, 8), S)).isEqualTo(old);
    }

    @Test
    void aMoveOverTwoMinutesAndTenPercentIsShown() {
        Shown old = shown(20, 30, 4); // middle 25 min, 10% is 2.5 min

        // middle 21 min: moved 4 min, 16%
        assertThat(EtaSmoother.smooth(old, shown(16, 26, 4), S)).isEqualTo(shown(16, 26, 4));
    }

    @Test
    void aMoveOfExactlyTwoMinutesAndTenPercentIsShown() {
        Shown old = shown(15, 25, 4); // middle 20 min, 10% is 2 min

        assertThat(EtaSmoother.smooth(old, shown(13, 23, 4), S)).isEqualTo(shown(13, 23, 4));
    }

    @Test
    void aMoveOfTwoMinutesOrMoreButUnderTenPercentIsIgnoredForLongWaits() {
        Shown old = shown(100, 140, 15); // middle 120 min, 10% is 12 min

        assertThat(EtaSmoother.smooth(old, shown(90, 130, 15), S)).isEqualTo(old);
    }

    @Test
    void aChangeOfPositionIsShownEvenIfTheMiddleBarelyMoved() {
        Shown old = shown(20, 30, 4);

        assertThat(EtaSmoother.smooth(old, shown(20, 30, 3), S)).isEqualTo(shown(20, 30, 3));
    }

    @Test
    void theUpperEndRisesByAtMostFiveMinutesWhenThePositionImproved() {
        Shown old = shown(20, 30, 4);

        // position 4 -> 3 but the new range is far later: upper end limited to 30 + 5
        assertThat(EtaSmoother.smooth(old, shown(40, 60, 3), S)).isEqualTo(shown(35, 35, 3));
    }

    @Test
    void theUpperEndMayRiseFreelyWhenThePositionGotWorse() {
        Shown old = shown(20, 30, 4);

        assertThat(EtaSmoother.smooth(old, shown(40, 60, 5), S)).isEqualTo(shown(40, 60, 5));
    }

    @Test
    void theUpperEndIsCappedForALargeMoveAtTheSamePosition() {
        Shown old = shown(20, 30, 4); // middle 25

        // middle 50 at the same position: shown, but the upper end rises by 5 min only; low end follows below it
        assertThat(EtaSmoother.smooth(old, shown(40, 60, 4), S)).isEqualTo(shown(35, 35, 4));
    }

    @Test
    void aLowerUpperEndIsNeverRaisedByTheCap() {
        Shown old = shown(20, 30, 4);

        assertThat(EtaSmoother.smooth(old, shown(5, 12, 4), S)).isEqualTo(shown(5, 12, 4));
    }

    @Test
    void whenTheShownMiddleIsZeroOnlyTheTwoMinuteRuleApplies() {
        Shown old = new Shown(0, 0, 1);

        // 10% of zero is zero, so the 2 minute rule decides: a middle of 1.5 min is ignored, 2 min is shown
        assertThat(EtaSmoother.smooth(old, new Shown(60, 120, 1), S)).isEqualTo(old);
        assertThat(EtaSmoother.smooth(old, shown(1, 3, 1), S)).isEqualTo(shown(1, 3, 1));
    }

    @Test
    void aShownRangeIsValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Shown(-1, 5, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Shown(10, 5, 1));
        assertThatNullPointerException().isThrownBy(() -> EtaSmoother.smooth(null, null, S));
    }
}
