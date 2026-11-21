package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class RangeCalibrationTest {

    @Test
    void widensWhenTooFewActualWaitsFallInsideTheRange() {
        assertThat(RangeCalibration.adjust(1.0, 45, 100)).isEqualTo(1.1);
    }

    @Test
    void narrowsWhenTooManyFallInside() {
        assertThat(RangeCalibration.adjust(1.5, 80, 100)).isEqualTo(1.4);
    }

    @Test
    void staysPutInsideTheTargetBandIncludingItsEdges() {
        assertThat(RangeCalibration.adjust(1.3, 60, 100)).isEqualTo(1.3);
        assertThat(RangeCalibration.adjust(1.3, 65, 100)).isEqualTo(1.3);
        assertThat(RangeCalibration.adjust(1.3, 70, 100)).isEqualTo(1.3);
    }

    @Test
    void neverLeavesItsBounds() {
        assertThat(RangeCalibration.adjust(2.5, 0, 100)).isEqualTo(2.5);
        assertThat(RangeCalibration.adjust(1.0, 100, 100)).isEqualTo(1.0);
    }

    @Test
    void tooFewPredictionsLeaveTheFactorAlone() {
        assertThat(RangeCalibration.adjust(1.2, 0, RangeCalibration.MIN_PREDICTIONS - 1))
                .isEqualTo(1.2);
    }

    @Test
    void repeatedStepsDoNotDrift() {
        double f = 1.0;
        for (int i = 0; i < 7; i++) {
            f = RangeCalibration.adjust(f, 10, 100);
        }
        assertThat(f).isEqualTo(1.7);
    }

    @Test
    void rejectsImpossibleCounts() {
        assertThatThrownBy(() -> RangeCalibration.adjust(1.0, 5, 4)).isInstanceOf(IllegalArgumentException.class);
    }
}
