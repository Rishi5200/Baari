package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.eta.Backtest.Row;
import com.baari.eta.WaitReplay.Observation;
import com.baari.queue.Priority;
import com.baari.queue.VisitType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class BacktestTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate DAY2 = LocalDate.of(2026, 11, 2);
    private static final Backtest.Settings SETTINGS = new Backtest.Settings(1, 360, 20, 30, ZoneId.of("UTC"));

    private static Instant at(LocalDate day, String hhmmss) {
        return Instant.parse(day + "T" + hhmmss + "Z");
    }

    private static Row row(LocalDate day, int number, String joined, String called, String started, String completed) {
        return new Row(
                day,
                "Dr A",
                number,
                VisitType.NEW,
                Priority.NORMAL,
                "COMPLETED",
                at(day, joined),
                at(day, called),
                at(day, started),
                at(day, completed));
    }

    /** Twenty earlier consultations of exactly 10 minutes, so Rolling has something to learn from. */
    private static List<Row> history() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int startMin = 8 * 60 + i * 11;
            rows.add(row(
                    DAY1, i + 1, hms(startMin - 1, 0), hms(startMin, 0), hms(startMin, 30), hms(startMin + 10, 30)));
        }
        return rows;
    }

    private static String hms(int minutes, int seconds) {
        return String.format("%02d:%02d:%02d", minutes / 60, minutes % 60, seconds);
    }

    private static List<Row> testDay() {
        return List.of(
                row(DAY2, 1, "09:00:00", "09:00:00", "09:00:30", "09:10:30"),
                row(DAY2, 2, "09:02:00", "09:10:30", "09:11:00", "09:16:00"));
    }

    private static Observation find(List<Observation> obs, String estimator, String trigger, int predicted) {
        return obs.stream()
                .filter(o ->
                        o.variant().equals(estimator) && o.trigger().equals(trigger) && o.predictedS() == predicted)
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError(estimator + " " + trigger + " " + predicted + " not found in " + obs));
    }

    @Test
    void scoresEachPredictionAgainstTheWaitThatFollowed() {
        List<Row> rows = new ArrayList<>(history());
        rows.addAll(testDay());

        Backtest.Result result = Backtest.run(rows, SETTINGS);

        assertThat(result.testDays()).containsExactly(DAY2);
        // patient 1 joins at 09:00 to an empty room and is seen 30 s later; the projector says "now"
        Observation first = find(result.observations(), "DEFAULT", "JOIN", 0);
        assertThat(first.actualS()).isEqualTo(30);
        // patient 2 joins at 09:02 while patient 1 has been in the room for 90 s of an expected 360
        Observation second = find(result.observations(), "DEFAULT", "JOIN", 270);
        assertThat(second.actualS()).isEqualTo(540);
        assertThat(second.errorS()).isEqualTo(-270);
    }

    @Test
    void rollingLearnsFromEarlierDaysOnlyNotFromTheDayBeingScored() {
        List<Row> rows = new ArrayList<>(history());
        rows.addAll(testDay());

        List<Observation> obs = Backtest.run(rows, SETTINGS).observations();

        // earlier days took ten minutes each, so with 90 s of the current consultation gone 510 s
        // remain; 270 would be the Default answer, which Rolling must not give once it has history
        assertThat(find(obs, "ROLLING", "JOIN", 510).actualS()).isEqualTo(540);
        assertThat(obs.stream()
                        .filter(o ->
                                o.variant().equals("ROLLING") && o.trigger().equals("JOIN")))
                .noneMatch(o -> o.predictedS() == 270);
    }

    @Test
    void noShowsAreNotScoredAndNeedMoreDaysThanTestDays() {
        List<Row> rows = new ArrayList<>(history());
        rows.add(new Row(
                DAY2,
                "Dr A",
                1,
                VisitType.NEW,
                Priority.NORMAL,
                "NO_SHOW",
                at(DAY2, "09:00:00"),
                at(DAY2, "09:00:00"),
                null,
                null));

        assertThat(Backtest.run(rows, SETTINGS).observations()).isEmpty();
        assertThatThrownBy(() -> Backtest.run(history(), SETTINGS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statsComputeErrorsAndCoverage() {
        List<Observation> obs = List.of(
                new Observation("X", "JOIN", 600, 500, 700, 660), // -1 min, covered
                new Observation("X", "JOIN", 600, 500, 700, 900), // -5 min, not covered
                new Observation("X", "JOIN", 600, 500, 700, 480)); // +2 min, not covered
        WaitReplay.Stats s = WaitReplay.stats(obs);
        assertThat(s.n()).isEqualTo(3);
        assertThat(s.maeMin()).isCloseTo((1 + 5 + 2) / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.biasMin()).isCloseTo((-1 - 5 + 2) / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.coverage()).isCloseTo(1 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
