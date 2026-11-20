package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.baari.eta.RollingStatsEstimator.Sample;
import com.baari.queue.VisitType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class RollingStatsEstimatorTest {

    private static final UUID DOCTOR_A = new UUID(1, 1);
    private static final UUID DOCTOR_B = new UUID(1, 2);
    private static final DefaultEstimator DEFAULT = new DefaultEstimator(360);
    private static final int MIN = 5;

    private static List<Sample> samples(int count, UUID doctor, VisitType visit, int hour, int seconds) {
        List<Sample> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Sample(doctor, visit, hour, seconds));
        }
        return list;
    }

    private static List<Sample> concat(List<Sample>... parts) {
        List<Sample> all = new ArrayList<>();
        for (List<Sample> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static RollingStatsEstimator estimator(List<Sample> samples) {
        return new RollingStatsEstimator(samples, MIN, DEFAULT);
    }

    @Test
    void withTooFewSamplesTheFallbackAnswersAndTheClinicIsNotReady() {
        RollingStatsEstimator estimator = estimator(samples(MIN - 1, DOCTOR_A, VisitType.NEW, 9, 100));

        assertThat(estimator.hasEnoughData()).isFalse();
        assertThat(estimator.estimate(new ServiceContext(DOCTOR_A, VisitType.NEW, 9)))
                .isEqualTo(DEFAULT.estimate(null));
    }

    @Test
    void withEnoughSamplesTheClinicIsReady() {
        assertThat(estimator(samples(MIN, DOCTOR_A, VisitType.NEW, 9, 100)).hasEnoughData())
                .isTrue();
    }

    @Test
    void aGroupMeanAndQuantilesAreComputedFromItsSamples() {
        List<Sample> list = new ArrayList<>();
        for (int seconds : new int[] {100, 200, 300, 400, 500}) {
            list.add(new Sample(DOCTOR_A, VisitType.NEW, 9, seconds));
        }

        Distribution d = estimator(list).estimate(new ServiceContext(DOCTOR_A, VisitType.NEW, 9));

        // sorted 100..500: p20 at index 0.8 = 100 + 0.8 x 100; p80 at index 3.2 = 400 + 0.2 x 100
        assertThat(d.meanS()).isEqualTo(300);
        assertThat(d.p20S()).isCloseTo(180, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(d.p80S()).isCloseTo(420, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void theMostSpecificGroupWithEnoughSamplesWins() {
        List<Sample> list = concat(
                samples(MIN, DOCTOR_A, VisitType.NEW, 9, 100), // doctor, visit, hour
                samples(MIN, DOCTOR_A, VisitType.NEW, 10, 200), // same doctor and visit, other hour
                samples(MIN, DOCTOR_B, VisitType.NEW, 9, 400)); // other doctor
        RollingStatsEstimator estimator = estimator(list);

        assertThat(estimator
                        .estimate(new ServiceContext(DOCTOR_A, VisitType.NEW, 9))
                        .meanS())
                .isEqualTo(100);
    }

    @Test
    void withoutEnoughSamplesForTheHourItUsesTheDoctorAndVisitType() {
        List<Sample> list = concat(
                samples(MIN - 1, DOCTOR_A, VisitType.NEW, 9, 100),
                samples(MIN, DOCTOR_A, VisitType.NEW, 10, 200),
                samples(MIN, DOCTOR_B, VisitType.NEW, 9, 400));

        // hour 9 has 4 samples for doctor A; doctor A with NEW has 9 samples (4 x 100, 5 x 200)
        assertThat(estimator(list)
                        .estimate(new ServiceContext(DOCTOR_A, VisitType.NEW, 9))
                        .meanS())
                .isCloseTo((4 * 100 + 5 * 200) / 9.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void withoutEnoughSamplesForTheVisitTypeItUsesTheDoctor() {
        List<Sample> list = concat(
                samples(MIN, DOCTOR_A, VisitType.NEW, 9, 100),
                samples(2, DOCTOR_A, VisitType.FOLLOW_UP, 9, 40),
                samples(MIN, DOCTOR_B, VisitType.FOLLOW_UP, 9, 400));

        // doctor A, FOLLOW_UP: 2 samples; doctor A overall: 7 samples (5 x 100, 2 x 40)
        assertThat(estimator(list)
                        .estimate(new ServiceContext(DOCTOR_A, VisitType.FOLLOW_UP, 9))
                        .meanS())
                .isCloseTo((5 * 100 + 2 * 40) / 7.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aDoctorWithTooFewSamplesFallsBackToTheClinicForTheVisitType() {
        List<Sample> list = concat(
                samples(2, DOCTOR_A, VisitType.NEW, 9, 100),
                samples(MIN, DOCTOR_B, VisitType.NEW, 9, 400),
                samples(MIN, DOCTOR_B, VisitType.FOLLOW_UP, 9, 40));

        // clinic-wide NEW visits: 2 x 100 + 5 x 400
        assertThat(estimator(list)
                        .estimate(new ServiceContext(DOCTOR_A, VisitType.NEW, 9))
                        .meanS())
                .isCloseTo((2 * 100 + 5 * 400) / 7.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aVisitTypeWithTooFewSamplesFallsBackToTheWholeClinic() {
        List<Sample> list =
                concat(samples(MIN, DOCTOR_B, VisitType.NEW, 9, 400), samples(2, DOCTOR_B, VisitType.FOLLOW_UP, 9, 40));

        assertThat(estimator(list)
                        .estimate(new ServiceContext(DOCTOR_A, VisitType.FOLLOW_UP, 9))
                        .meanS())
                .isCloseTo((5 * 400 + 2 * 40) / 7.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void withoutADoctorOnlyClinicGroupsAreUsed() {
        List<Sample> list =
                concat(samples(MIN, DOCTOR_A, VisitType.NEW, 9, 100), samples(MIN, DOCTOR_B, VisitType.NEW, 9, 300));

        assertThat(estimator(list)
                        .estimate(new ServiceContext(null, VisitType.NEW, 9))
                        .meanS())
                .isEqualTo(200);
    }

    @Test
    void anUnknownDoctorUsesTheClinicGroups() {
        List<Sample> list = samples(MIN, DOCTOR_A, VisitType.NEW, 9, 100);

        assertThat(estimator(list)
                        .estimate(new ServiceContext(DOCTOR_B, VisitType.NEW, 9))
                        .meanS())
                .isEqualTo(100);
    }

    @Test
    void aClinicWithNoSamplesAtAllUsesTheFallback() {
        assertThat(estimator(List.of()).estimate(new ServiceContext(null, VisitType.NEW, 9)))
                .isEqualTo(DEFAULT.estimate(null));
    }

    @Test
    void consultationsTooShortOrTooLongAreDroppedAndCounted() {
        List<Sample> list = concat(
                samples(MIN, DOCTOR_A, VisitType.NEW, 9, 100),
                samples(1, DOCTOR_A, VisitType.NEW, 9, 19),
                samples(1, DOCTOR_A, VisitType.NEW, 9, 3601),
                samples(1, DOCTOR_A, VisitType.NEW, 9, 20),
                samples(1, DOCTOR_A, VisitType.NEW, 9, 3600));
        RollingStatsEstimator estimator = estimator(list);

        assertThat(estimator.droppedOutliers()).isEqualTo(2);
        // the limits themselves (20 s and 3600 s) are kept: 5 x 100 + 20 + 3600 over 7 samples
        assertThat(estimator
                        .estimate(new ServiceContext(DOCTOR_A, VisitType.NEW, 9))
                        .meanS())
                .isCloseTo((5 * 100 + 20 + 3600) / 7.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void onlyTheNewestTwoHundredSamplesOfAGroupCount() {
        // newest first: 200 recent ones at 100 s, then 50 old ones at 1000 s
        List<Sample> list =
                concat(samples(200, DOCTOR_A, VisitType.NEW, 9, 100), samples(50, DOCTOR_A, VisitType.NEW, 9, 1000));

        assertThat(estimator(list)
                        .estimate(new ServiceContext(DOCTOR_A, VisitType.NEW, 9))
                        .meanS())
                .isEqualTo(100);
    }

    @Test
    void theSameEstimatorGivesTheSameAnswerEveryTime() {
        RollingStatsEstimator estimator = estimator(samples(MIN, DOCTOR_A, VisitType.NEW, 9, 100));
        ServiceContext ctx = new ServiceContext(DOCTOR_A, VisitType.NEW, 9);

        assertThat(estimator.estimate(ctx)).isEqualTo(estimator.estimate(ctx));
    }

    @Test
    void itHasAName() {
        assertThat(estimator(List.of()).name()).isEqualTo("ROLLING");
        assertThat(DEFAULT.name()).isEqualTo("DEFAULT");
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RollingStatsEstimator(List.of(), 0, DEFAULT));
        assertThatNullPointerException().isThrownBy(() -> new RollingStatsEstimator(List.of(), 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new DefaultEstimator(0));
    }

    @Test
    void theDefaultEstimatorIsWide() {
        Distribution d = DEFAULT.estimate(new ServiceContext(null, VisitType.NEW, 9));

        assertThat(d.meanS()).isEqualTo(360);
        assertThat(d.p20S()).isCloseTo(216, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(d.p80S()).isCloseTo(540, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void distributionsAndContextsAreValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Distribution(0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Distribution(10, -1, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> new Distribution(10, 6, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> new ServiceContext(null, VisitType.NEW, 24));
        assertThatNullPointerException().isThrownBy(() -> new ServiceContext(null, null, 3));
        assertThat(new Distribution(300, 240, 360).sdS()).isCloseTo(71.3, org.assertj.core.data.Offset.offset(0.05));
    }
}
