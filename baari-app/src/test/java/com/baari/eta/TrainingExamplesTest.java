package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;

import com.baari.queue.HistoricalToken;
import com.baari.queue.Priority;
import com.baari.queue.TokenStatus;
import com.baari.queue.VisitType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Training features must describe each consultation as it looked when it started (PLAN §8.6, Phase
 * 7 "Done when"): nothing that happened later may change them.
 */
@Tag("unit")
class TrainingExamplesTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2); // a Monday
    private static final UUID QUEUE_DAY = new UUID(1, 1);
    private static final UUID DOCTOR = new UUID(2, 2);

    private static Instant at(String hhmm) {
        return Instant.parse(DAY + "T" + hhmm + ":00Z");
    }

    private static HistoricalToken token(
            int number, String joined, String called, String started, String completed, TokenStatus status) {
        return new HistoricalToken(
                new UUID(9, number),
                QUEUE_DAY,
                DAY,
                called == null ? null : DOCTOR,
                number,
                number % 2 == 0 ? VisitType.FOLLOW_UP : VisitType.NEW,
                number == 3 ? Priority.PRIORITY : Priority.NORMAL,
                status,
                at(joined),
                called == null ? null : at(called),
                started == null ? null : at(started),
                completed == null ? null : at(completed),
                null,
                false);
    }

    /** Four patients join at 09:00..09:15; the doctor sees them one after another from 09:30. */
    private static List<HistoricalToken> day() {
        return List.of(
                token(1, "09:00", "09:30", "09:30", "09:40", TokenStatus.COMPLETED),
                token(2, "09:05", "09:40", "09:41", "09:48", TokenStatus.COMPLETED),
                token(3, "09:10", "09:48", "09:49", "10:01", TokenStatus.COMPLETED),
                token(4, "09:15", "10:01", "10:02", "10:09", TokenStatus.COMPLETED));
    }

    @Test
    void describesEachConsultationAsItLookedWhenItStarted() {
        List<TrainingExamples.Example> examples = TrainingExamples.from(day(), UTC);

        assertThat(examples).hasSize(4);
        ServiceContext third = examples.get(2).context();
        assertThat(third.doctorId()).isEqualTo(DOCTOR);
        assertThat(third.priority()).isEqualTo(Priority.PRIORITY);
        assertThat(third.hourOfDay()).isEqualTo(9);
        assertThat(third.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(third.positionInSession()).isEqualTo(3);
        assertThat(third.minutesIntoSession()).isEqualTo(19);
        assertThat(third.queueLength())
                .as("only patient 4 still waits at 09:49")
                .isEqualTo(1);
        assertThat(examples.get(2).serviceSeconds()).isEqualTo(12 * 60);
        assertThat(examples.get(0).context().queueLength())
                .as("2, 3 and 4 wait at 09:30")
                .isEqualTo(3);
    }

    @Test
    void laterEventsDoNotChangeEarlierFeatures() {
        ServiceContext before = TrainingExamples.from(day(), UTC).get(1).context();

        // the future changes completely: patient 2 takes much longer, 3 and 4 are seen differently,
        // and two more patients join after patient 2 went in
        List<HistoricalToken> changed = new ArrayList<>(List.of(
                token(1, "09:00", "09:30", "09:30", "09:40", TokenStatus.COMPLETED),
                token(2, "09:05", "09:40", "09:41", "10:20", TokenStatus.COMPLETED),
                token(3, "09:10", "10:20", null, null, TokenStatus.NO_SHOW),
                token(4, "09:15", "10:25", "10:26", "10:30", TokenStatus.COMPLETED),
                token(5, "09:45", "10:30", "10:31", "10:40", TokenStatus.COMPLETED),
                token(6, "09:50", "10:40", "10:41", "10:45", TokenStatus.COMPLETED)));
        ServiceContext after = TrainingExamples.from(changed, UTC).get(1).context();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void theConsultationsOwnEndIsTheTargetNotAFeature() {
        List<HistoricalToken> longer = new ArrayList<>(day());
        longer.set(0, token(1, "09:00", "09:30", "09:30", "09:55", TokenStatus.COMPLETED));

        TrainingExamples.Example a = TrainingExamples.from(day(), UTC).get(0);
        TrainingExamples.Example b = TrainingExamples.from(longer, UTC).get(0);
        assertThat(b.context()).isEqualTo(a.context());
        assertThat(b.serviceSeconds()).isEqualTo(25 * 60).isNotEqualTo(a.serviceSeconds());
    }

    @Test
    void aLongPauseStartsANewSessionAndUnfinishedConsultationsAreNotLearnedFrom() {
        List<HistoricalToken> tokens = new ArrayList<>(day());
        tokens.add(token(5, "16:50", "17:00", "17:00", "17:08", TokenStatus.COMPLETED));
        tokens.add(token(6, "16:55", "17:08", "17:09", null, TokenStatus.COMPLETED)); // no end: not learned

        List<TrainingExamples.Example> examples = TrainingExamples.from(tokens, UTC);

        assertThat(examples).hasSize(5);
        ServiceContext evening = examples.get(4).context();
        assertThat(evening.positionInSession()).isEqualTo(1);
        assertThat(evening.minutesIntoSession()).isZero();
        assertThat(evening.queueLength()).as("patient 6 waits at 17:00").isEqualTo(1);
    }

    @Test
    void featuresAreNamedOneHotsAndScaledCounts() {
        ServiceContext ctx =
                new ServiceContext(DOCTOR, VisitType.NEW, 10, Priority.EMERGENCY, DayOfWeek.FRIDAY, 1, 90, 120);
        assertThat(ServiceFeatures.of(ctx))
                .containsEntry("doctor=" + DOCTOR, 1.0)
                .containsEntry("visit=NEW", 1.0)
                .containsEntry("priority=EMERGENCY", 1.0)
                .containsEntry("hour=10", 1.0)
                .containsEntry("dow=FRIDAY", 1.0)
                .containsEntry("position", 0.1)
                .containsEntry("hoursIntoSession", 1.5)
                .containsEntry("queueLength", 5.0) // capped at 50
                .containsEntry("firstOfSession", 1.0);
    }
}
