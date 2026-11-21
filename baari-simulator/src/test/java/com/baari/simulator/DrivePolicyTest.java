package com.baari.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.simulator.DrivePolicy.Action;
import com.baari.simulator.DrivePolicy.Board;
import com.baari.simulator.DrivePolicy.CallNext;
import com.baari.simulator.DrivePolicy.Complete;
import com.baari.simulator.DrivePolicy.OpenDay;
import com.baari.simulator.DrivePolicy.Start;
import com.baari.simulator.DrivePolicy.Tok;
import com.baari.simulator.DrivePolicy.WalkIn;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DrivePolicyTest {

    private static final SimConfig.Doctor DOCTOR = new SimConfig.Doctor("A", 420, 240, 0.4, 1.0);
    private static final Instant T0 = Instant.parse("2026-11-21T05:00:00Z");
    private static final Duration TICK = Duration.ofSeconds(5);

    /** No arrivals, so only the doctor acts. */
    private static DrivePolicy quiet() {
        return new DrivePolicy(new DrivePolicy.Settings(DOCTOR, 0, 0.4, 0.1, 6, 1), new Random(1));
    }

    private static Tok waiting(String id) {
        return new Tok(id, "NEW", null, null);
    }

    @Test
    void opensTheDayWhenThereIsNone() {
        assertThat(quiet().decide(Board.noDay(), T0, TICK)).containsExactly(new OpenDay());
    }

    @Test
    void leavesAPausedOrClosedDayAlone() {
        Board paused = new Board("PAUSED", List.of(waiting("w1")), List.of(), List.of());
        Board closed = new Board("CLOSED", List.of(waiting("w1")), List.of(), List.of());
        assertThat(quiet().decide(paused, T0, TICK)).isEmpty();
        assertThat(quiet().decide(closed, T0, TICK)).isEmpty();
    }

    @Test
    void callsNextWhenTheDoctorIsFree() {
        Board board = new Board("OPEN", List.of(waiting("w1")), List.of(), List.of());
        assertThat(quiet().decide(board, T0, TICK)).containsExactly(new CallNext());
    }

    @Test
    void doesNothingOnAnEmptyQueue() {
        assertThat(quiet().decide(new Board("OPEN", List.of(), List.of(), List.of()), T0, TICK))
                .isEmpty();
    }

    @Test
    void startsACalledPatientAfterTheWalkToTheRoom() {
        DrivePolicy policy = quiet();
        Board board = new Board("OPEN", List.of(waiting("w1")), List.of(new Tok("c1", "NEW", T0, null)), List.of());

        // still walking: nothing, and nobody else is called meanwhile
        assertThat(policy.decide(board, T0.plusSeconds(DrivePolicy.WALK_MIN_S - 1), TICK))
                .isEmpty();
        // the walk takes at most WALK_MAX_S
        assertThat(policy.decide(board, T0.plusSeconds(DrivePolicy.WALK_MAX_S), TICK))
                .containsExactly(new Start("c1"));
    }

    @Test
    void completesAConsultationAfterItsPlannedLength() {
        DrivePolicy policy = quiet();
        Board board = new Board("OPEN", List.of(), List.of(), List.of(new Tok("i1", "NEW", T0, T0)));

        assertThat(policy.decide(board, T0.plusSeconds(1), TICK)).isEmpty();
        // the plan is kept between ticks: lognormal with sigma 0.4 stays under 30x the median
        assertThat(policy.decide(board, T0.plus(Duration.ofHours(4)), TICK)).containsExactly(new Complete("i1"));
    }

    @Test
    void pausesBetweenPatients() {
        DrivePolicy policy = quiet();
        Board board = new Board("OPEN", List.of(waiting("w1")), List.of(), List.of());
        policy.completed(T0);

        assertThat(policy.decide(board, T0.plusSeconds(DrivePolicy.GAP_S - 1), TICK))
                .isEmpty();
        assertThat(policy.decide(board, T0.plusSeconds(DrivePolicy.GAP_S), TICK))
                .containsExactly(new CallNext());
    }

    @Test
    void speedShortensConsultations() {
        DrivePolicy.Settings normal = new DrivePolicy.Settings(DOCTOR, 0, 0.4, 0.1, 6, 1);
        DrivePolicy.Settings fast = new DrivePolicy.Settings(DOCTOR, 0, 0.4, 0.1, 6, 4);
        // same random draws, a quarter of the time
        Duration a = new DrivePolicy(normal, new Random(9)).serviceTime("NEW");
        Duration b = new DrivePolicy(fast, new Random(9)).serviceTime("NEW");
        assertThat(b.toMillis()).isCloseTo(a.toMillis() / 4, org.assertj.core.data.Offset.offset(1L));
    }

    @Test
    void followUpsAreShorterOnAverage() {
        DrivePolicy policy = quiet();
        long newTotal = 0;
        long followUpTotal = 0;
        for (int i = 0; i < 2000; i++) {
            newTotal += policy.serviceTime("NEW").toSeconds();
            followUpTotal += policy.serviceTime("FOLLOW_UP").toSeconds();
        }
        assertThat(followUpTotal).isLessThan(newTotal);
    }

    @Test
    void arrivalsFollowTheRateAndStopAtMaxWaiting() {
        // 60 an hour over one-minute ticks is about one walk-in per tick
        DrivePolicy busy = new DrivePolicy(new DrivePolicy.Settings(DOCTOR, 60, 0.4, 0.1, 1000, 1), new Random(3));
        Board empty = new Board("OPEN", List.of(), List.of(), List.of());
        int walkIns = 0;
        for (int i = 0; i < 600; i++) {
            walkIns += (int) busy.decide(empty, T0, Duration.ofMinutes(1)).stream()
                    .filter(a -> a instanceof WalkIn)
                    .count();
        }
        assertThat(walkIns).isBetween(500, 700);

        // a queue already at the cap gets no walk-ins
        DrivePolicy capped = new DrivePolicy(new DrivePolicy.Settings(DOCTOR, 6000, 0.4, 0.1, 2, 1), new Random(3));
        Board full = new Board(
                "OPEN", List.of(waiting("w1"), waiting("w2")), List.of(new Tok("c1", "NEW", T0, null)), List.of());
        assertThat(capped.decide(full, T0, TICK)).noneMatch(a -> a instanceof WalkIn);
        // one place left: at most one walk-in, however many arrive
        Board oneLeft = new Board("OPEN", List.of(waiting("w1")), List.of(new Tok("c1", "NEW", T0, null)), List.of());
        assertThat(capped.decide(oneLeft, T0, TICK).stream().filter(a -> a instanceof WalkIn))
                .hasSize(1);
    }

    @Test
    void walkInsHaveAPlausibleNameVisitAndPriority() {
        DrivePolicy busy = new DrivePolicy(new DrivePolicy.Settings(DOCTOR, 6000, 0.5, 0.2, 1000, 1), new Random(5));
        List<WalkIn> walkIns = new ArrayList<>();
        for (Action a : busy.decide(new Board("OPEN", List.of(), List.of(), List.of()), T0, Duration.ofMinutes(1))) {
            if (a instanceof WalkIn w) {
                walkIns.add(w);
            }
        }
        assertThat(walkIns).hasSizeGreaterThan(50);
        assertThat(walkIns).allSatisfy(w -> {
            assertThat(w.name()).matches("[A-Z][a-z]+ [A-Z]\\.");
            assertThat(w.visitType()).isIn("NEW", "FOLLOW_UP");
            assertThat(w.priority()).isIn("NORMAL", "PRIORITY");
        });
        assertThat(walkIns).anyMatch(w -> w.priority().equals("PRIORITY"));
        assertThat(walkIns).anyMatch(w -> w.visitType().equals("FOLLOW_UP"));
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThatThrownBy(() -> new DrivePolicy.Settings(DOCTOR, 1, 0.4, 0.1, 6, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DrivePolicy.Settings(DOCTOR, -1, 0.4, 0.1, 6, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheBoardTheServerSends() {
        String body = """
                {"day":{"id":"d","status":"OPEN","closedAt":null,"lastNumber":3},
                 "waiting":[{"id":"w1","visitType":"FOLLOW_UP","calledAt":null,"consultStartedAt":null,"flags":[]}],
                 "called":[{"id":"c1","visitType":"NEW","calledAt":"2026-11-21T05:00:00Z","consultStartedAt":null,"flags":[]}],
                 "inConsultation":[],"noShow":[],"finished":[]}
                """;
        Board board = LiveDriver.parseBoard(body);
        assertThat(board.dayStatus()).isEqualTo("OPEN");
        assertThat(board.waiting()).containsExactly(new Tok("w1", "FOLLOW_UP", null, null));
        assertThat(board.called()).containsExactly(new Tok("c1", "NEW", T0, null));
        assertThat(board.inConsultation()).isEmpty();
    }

    @Test
    void busyHourRateAveragesTheHoursWithArrivals() {
        SimConfig cfg = SimConfig.parse("""
                { "clinic": "T", "sessions": [ { "start": "09:00", "end": "11:00" } ],
                  "doctors": [ { "name": "A", "medianNewS": 1, "medianFollowUpS": 1, "sigma": 0.1, "arrivalShare": 1 } ],
                  "fatiguePerPatient": 0, "lateStartMeanMin": 0, "arrivalsPerHour": { "9": 10, "10": 20 },
                  "newVisitShare": 0.5, "priorityShare": 0, "emergencyShare": 0, "noShowProbability": 0, "qrShare": 0 }
                """);
        assertThat(SimulatorMain.busyHourRate(cfg)).isEqualTo(15.0);
    }
}
