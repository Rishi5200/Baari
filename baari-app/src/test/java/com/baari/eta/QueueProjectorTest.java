package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.baari.eta.QueueProjector.Called;
import com.baari.eta.QueueProjector.Consult;
import com.baari.eta.QueueProjector.Input;
import com.baari.eta.QueueProjector.Params;
import com.baari.eta.QueueProjector.Projection;
import com.baari.eta.QueueProjector.Server;
import com.baari.queue.NextTokenPolicy;
import com.baari.queue.Priority;
import com.baari.queue.QueueSnapshot;
import com.baari.queue.TokenStatus;
import com.baari.queue.TokenView;
import com.baari.queue.VisitType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Hand-computed examples (PLAN §8.4). Every number in a comment was worked out on paper. */
@Tag("unit")
class QueueProjectorTest {

    /** 09:30 in Kolkata. */
    private static final Instant NOW = Instant.parse("2026-11-14T04:00:00Z");

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final UUID DOCTOR_A = new UUID(1, 1);
    private static final UUID DOCTOR_B = new UUID(1, 2);
    private static final NextTokenPolicy.Settings POLICY = new NextTokenPolicy.Settings(2, 2);
    private static final Params NO_GAP = new Params(0, 0, 1);

    /** Every consultation takes exactly this long: no spread, so ranges collapse to a point. */
    private static ServiceTimeEstimator fixed(double meanSeconds) {
        return stub(new Distribution(meanSeconds, meanSeconds, meanSeconds), new ArrayList<>());
    }

    private static ServiceTimeEstimator stub(Distribution d, List<ServiceContext> seen) {
        return new ServiceTimeEstimator() {
            @Override
            public Distribution estimate(ServiceContext context) {
                seen.add(context);
                return d;
            }

            @Override
            public String name() {
                return "STUB";
            }
        };
    }

    private static TokenView waiting(int number, Priority priority) {
        return new TokenView(
                new UUID(0, number),
                number,
                priority,
                TokenStatus.WAITING,
                TokenView.initialSortKey(number),
                NOW.minusSeconds(600 - number));
    }

    private static Input input(List<Server> servers, List<Called> called, TokenView... waiting) {
        return new Input(
                NOW,
                KOLKATA,
                servers,
                called,
                new QueueSnapshot(List.of(waiting)),
                new HashMap<>(),
                NextTokenPolicy.State.INITIAL,
                POLICY);
    }

    private static List<Integer> waits(List<Projection> projections) {
        return projections.stream().map(Projection::waitSeconds).toList();
    }

    private static Server free(UUID doctor) {
        return new Server(doctor, null);
    }

    // --- one doctor ---

    @Test
    void oneDoctorThreePatientsAtFiveMinutesStartAtZeroFiveAndTen() {
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(free(DOCTOR_A)),
                        List.of(),
                        waiting(1, Priority.NORMAL),
                        waiting(2, Priority.NORMAL),
                        waiting(3, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        assertThat(waits(result)).containsExactly(0, 300, 600);
        assertThat(result).extracting(Projection::position).containsExactly(1, 2, 3);
        assertThat(result.get(1).tokenId()).isEqualTo(new UUID(0, 2));
        assertThat(result.get(2).expectedStartAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void theGapBetweenConsultationsIsAddedAfterEachPatient() {
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(free(DOCTOR_A)),
                        List.of(),
                        waiting(1, Priority.NORMAL),
                        waiting(2, Priority.NORMAL),
                        waiting(3, Priority.NORMAL)),
                fixed(300),
                new Params(30, 0, 1));

        assertThat(waits(result)).containsExactly(0, 330, 660);
    }

    @Test
    void noShowsTakeNoTimeSoAFifthOfPatientsShortensEachSlotByAFifth() {
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(free(DOCTOR_A)),
                        List.of(),
                        waiting(1, Priority.NORMAL),
                        waiting(2, Priority.NORMAL),
                        waiting(3, Priority.NORMAL)),
                fixed(300),
                new Params(0, 0.2, 1));

        // 0.8 x 300 = 240 per patient
        assertThat(waits(result)).containsExactly(0, 240, 480);
    }

    @Test
    void thePredictedOrderIsTheRealCallOrder() {
        // The emergency (number 2) is called before the normal patient who joined first.
        List<Projection> result = QueueProjector.project(
                input(List.of(free(DOCTOR_A)), List.of(), waiting(1, Priority.NORMAL), waiting(2, Priority.EMERGENCY)),
                fixed(300),
                NO_GAP);

        assertThat(result).extracting(Projection::tokenId).containsExactly(new UUID(0, 2), new UUID(0, 1));
        assertThat(waits(result)).containsExactly(0, 300);
    }

    @Test
    void nobodyWaitingGivesNothing() {
        assertThat(QueueProjector.project(input(List.of(free(DOCTOR_A)), List.of()), fixed(300), NO_GAP))
                .isEmpty();
    }

    @Test
    void noDoctorServingGivesNothing() {
        assertThat(QueueProjector.project(input(List.of(), List.of(), waiting(1, Priority.NORMAL)), fixed(300), NO_GAP))
                .isEmpty();
    }

    // --- pool ---

    @Test
    void twoDoctorsHalveTheWait() {
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(free(DOCTOR_A), free(DOCTOR_B)),
                        List.of(),
                        waiting(1, Priority.NORMAL),
                        waiting(2, Priority.NORMAL),
                        waiting(3, Priority.NORMAL),
                        waiting(4, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        assertThat(waits(result)).containsExactly(0, 0, 300, 300);
    }

    // --- doctors already busy ---

    @Test
    void doctorFourMinutesIntoAFiveMinuteConsultationFreesUpInOneQuarterMinute() {
        Consult current = new Consult(new UUID(9, 9), VisitType.NEW, NOW.minusSeconds(240));
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(new Server(DOCTOR_A, current)),
                        List.of(),
                        waiting(1, Priority.NORMAL),
                        waiting(2, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        // remaining = max(300 - 240, 0.25 x 300) = max(60, 75) = 75
        assertThat(waits(result)).containsExactly(75, 375);
    }

    @Test
    void doctorWhoIsEarlyInTheConsultationFreesUpWhenItIsExpectedToEnd() {
        Consult current = new Consult(new UUID(9, 9), VisitType.NEW, NOW.minusSeconds(60));
        List<Projection> result = QueueProjector.project(
                input(List.of(new Server(DOCTOR_A, current)), List.of(), waiting(1, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        assertThat(waits(result)).containsExactly(240);
    }

    @Test
    void aConsultationRunningOverItsEstimateIsAssumedToNeedAtLeastAQuarterOfItMore() {
        Consult current = new Consult(new UUID(9, 9), VisitType.NEW, NOW.minusSeconds(1200));
        List<Projection> result = QueueProjector.project(
                input(List.of(new Server(DOCTOR_A, current)), List.of(), waiting(1, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        assertThat(waits(result)).containsExactly(75);
    }

    @Test
    void aConsultationThatStartsInTheFutureCountsAsJustStarted() {
        // Clock skew between instances: never a negative elapsed time.
        Consult current = new Consult(new UUID(9, 9), VisitType.NEW, NOW.plusSeconds(30));
        List<Projection> result = QueueProjector.project(
                input(List.of(new Server(DOCTOR_A, current)), List.of(), waiting(1, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        assertThat(waits(result)).containsExactly(300);
    }

    @Test
    void aCalledPatientWhoHasNotStartedYetTakesTheDoctorsNextSlot() {
        Called called = new Called(new UUID(8, 8), VisitType.NEW, NOW.minusSeconds(20), DOCTOR_A);
        List<Projection> result = QueueProjector.project(
                input(List.of(free(DOCTOR_A)), List.of(called), waiting(1, Priority.NORMAL)), fixed(300), NO_GAP);

        assertThat(waits(result)).containsExactly(300);
    }

    @Test
    void aCalledPatientBooksTheDoctorTheyWereCalledTo() {
        Called called = new Called(new UUID(8, 8), VisitType.NEW, NOW.minusSeconds(20), DOCTOR_B);
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(free(DOCTOR_A), free(DOCTOR_B)),
                        List.of(called),
                        waiting(1, Priority.NORMAL),
                        waiting(2, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        // B is booked for the called patient until 300, so patient 1 starts at once with A; patient 2
        // then waits for whichever doctor frees first (A and B both at 300).
        assertThat(waits(result)).containsExactly(0, 300);
    }

    @Test
    void aCalledPatientWithoutADoctorTakesTheEarliestFreeOne() {
        Called called = new Called(new UUID(8, 8), VisitType.NEW, NOW.minusSeconds(20), null);
        List<Projection> result = QueueProjector.project(
                input(List.of(free(DOCTOR_A)), List.of(called), waiting(1, Priority.NORMAL)), fixed(300), NO_GAP);

        assertThat(waits(result)).containsExactly(300);
    }

    @Test
    void calledPatientsAreBookedInTheOrderTheyWereCalled() {
        Called first = new Called(new UUID(8, 1), VisitType.NEW, NOW.minusSeconds(100), null);
        Called second = new Called(new UUID(8, 2), VisitType.NEW, NOW.minusSeconds(50), null);
        Called neverCalledAt = new Called(new UUID(8, 3), VisitType.NEW, null, null);
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(free(DOCTOR_A), free(DOCTOR_B)),
                        List.of(neverCalledAt, second, first),
                        waiting(1, Priority.NORMAL)),
                fixed(300),
                NO_GAP);

        // three called patients on two doctors: A and B take one each, then A takes the third (until 600)
        assertThat(waits(result)).containsExactly(300);
    }

    // --- ranges ---

    @Test
    void theRangeGrowsWithTheNumberOfConsultationsAhead() {
        Distribution spread = new Distribution(300, 240, 360);
        List<Projection> result = QueueProjector.project(
                input(
                        List.of(free(DOCTOR_A)),
                        List.of(),
                        waiting(1, Priority.NORMAL),
                        waiting(2, Priority.NORMAL),
                        waiting(3, Priority.NORMAL)),
                stub(spread, new ArrayList<>()),
                NO_GAP);

        // sd = (360 - 240) / 1.683 = 71.3 s; one ahead: +-0.8416 x 71.3 = +-60; two ahead: +-60 x sqrt(2) = +-84.85
        assertThat(result.get(0)).satisfies(p -> {
            assertThat(p.rangeMinSeconds()).isZero();
            assertThat(p.rangeMaxSeconds()).isZero();
        });
        assertThat(result.get(1)).satisfies(p -> {
            assertThat(p.waitSeconds()).isEqualTo(300);
            assertThat(p.rangeMinSeconds()).isEqualTo(240);
            assertThat(p.rangeMaxSeconds()).isEqualTo(360);
        });
        assertThat(result.get(2)).satisfies(p -> {
            assertThat(p.waitSeconds()).isEqualTo(600);
            assertThat(p.rangeMinSeconds()).isEqualTo(515);
            assertThat(p.rangeMaxSeconds()).isEqualTo(685);
        });
    }

    @Test
    void calibrationWidensTheRange() {
        Distribution spread = new Distribution(300, 240, 360);
        List<Projection> result = QueueProjector.project(
                input(List.of(free(DOCTOR_A)), List.of(), waiting(1, Priority.NORMAL), waiting(2, Priority.NORMAL)),
                stub(spread, new ArrayList<>()),
                new Params(0, 0, 2));

        assertThat(result.get(1).rangeMinSeconds()).isEqualTo(180);
        assertThat(result.get(1).rangeMaxSeconds()).isEqualTo(420);
    }

    @Test
    void theLowEndOfTheRangeNeverGoesBelowNow() {
        Distribution wide = new Distribution(300, 0, 900);
        Consult current = new Consult(new UUID(9, 9), VisitType.NEW, NOW);
        List<Projection> result = QueueProjector.project(
                input(List.of(new Server(DOCTOR_A, current)), List.of(), waiting(1, Priority.NORMAL)),
                stub(wide, new ArrayList<>()),
                NO_GAP);

        // wait 300, sd = 900 / 1.683 = 534.8, half = 450: 300 - 450 would be negative
        assertThat(result.get(0).rangeMinSeconds()).isZero();
        assertThat(result.get(0).rangeMaxSeconds()).isEqualTo(750);
    }

    @Test
    void theUncertaintyOfAConsultationInProgressCountsToo() {
        Distribution spread = new Distribution(300, 240, 360);
        Consult current = new Consult(new UUID(9, 9), VisitType.NEW, NOW);
        List<Projection> result = QueueProjector.project(
                input(List.of(new Server(DOCTOR_A, current)), List.of(), waiting(1, Priority.NORMAL)),
                stub(spread, new ArrayList<>()),
                NO_GAP);

        assertThat(result.get(0).waitSeconds()).isEqualTo(300);
        assertThat(result.get(0).rangeMinSeconds()).isEqualTo(240);
        assertThat(result.get(0).rangeMaxSeconds()).isEqualTo(360);
    }

    // --- what the estimator is asked ---

    @Test
    void theEstimatorIsAskedWithTheDoctorVisitTypeAndLocalHourOfTheStart() {
        List<ServiceContext> seen = new ArrayList<>();
        Map<UUID, VisitType> visits = Map.of(new UUID(0, 1), VisitType.FOLLOW_UP, new UUID(0, 2), VisitType.NEW);
        Input in = new Input(
                NOW,
                KOLKATA,
                List.of(free(DOCTOR_A)),
                List.of(),
                new QueueSnapshot(List.of(waiting(1, Priority.NORMAL), waiting(2, Priority.NORMAL))),
                visits,
                NextTokenPolicy.State.INITIAL,
                POLICY);

        QueueProjector.project(in, stub(new Distribution(1800, 1800, 1800), seen), NO_GAP);

        // 04:00 UTC is 09:30 in Kolkata. Patient 1 starts then (hour 9), patient 2 half an hour later (10:00).
        // The doctor has seen nobody yet, so patient 1 opens the session and patient 2 is second in it,
        // 30 minutes in; one patient is still waiting when patient 1 goes in, none when patient 2 does.
        java.time.DayOfWeek day = NOW.atZone(KOLKATA).getDayOfWeek();
        assertThat(seen)
                .containsExactly(
                        new ServiceContext(DOCTOR_A, VisitType.FOLLOW_UP, 9, Priority.NORMAL, day, 1, 0, 1),
                        new ServiceContext(DOCTOR_A, VisitType.NEW, 10, Priority.NORMAL, day, 2, 30, 0));
    }

    @Test
    void aWaitingTokenWithoutAVisitTypeIsTreatedAsANewVisit() {
        List<ServiceContext> seen = new ArrayList<>();
        QueueProjector.project(
                input(List.of(free(DOCTOR_A)), List.of(), waiting(1, Priority.NORMAL)),
                stub(new Distribution(300, 300, 300), seen),
                NO_GAP);

        assertThat(seen).singleElement().satisfies(ctx -> {
            assertThat(ctx.doctorId()).isEqualTo(DOCTOR_A);
            assertThat(ctx.visitType()).isEqualTo(VisitType.NEW);
            assertThat(ctx.hourOfDay()).isEqualTo(9);
        });
    }

    // --- parameters ---

    @Test
    void invalidParametersAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Params(-1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Params(0, -0.1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Params(0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Params(0, 0, 0));
    }

    @Test
    void aDoctorAlreadyInSessionContinuesItsCountAndAPriorityPatientIsDescribedAsSuch() {
        List<ServiceContext> seen = new ArrayList<>();
        // the doctor started the session 40 minutes ago and has seen 3 patients, the 4th is inside now
        Instant sessionStart = NOW.minus(java.time.Duration.ofMinutes(40));
        Instant currentStart = NOW.minus(java.time.Duration.ofMinutes(2));
        Sessions.SoFar session = new Sessions.SoFar(4, sessionStart, currentStart);
        Server busy =
                new Server(DOCTOR_A, new QueueProjector.Consult(new UUID(9, 9), VisitType.NEW, currentStart), session);
        QueueProjector.project(
                input(List.of(busy), List.of(), waiting(1, Priority.PRIORITY)),
                stub(new Distribution(300, 300, 300), seen),
                NO_GAP);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).positionInSession()).as("the patient inside").isEqualTo(4);
        assertThat(seen.get(0).minutesIntoSession()).isEqualTo(38);
        assertThat(seen.get(1).positionInSession()).as("the next patient").isEqualTo(5);
        assertThat(seen.get(1).priority()).isEqualTo(Priority.PRIORITY);
        assertThat(seen.get(1).queueLength()).isZero();
    }
}
