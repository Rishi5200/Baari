package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.ClinicView;
import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorDetails;
import com.baari.clinic.NewClinic;
import com.baari.clinic.QrMode;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.QueueDetails;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.MutableClock;
import com.baari.common.NotFoundException;
import com.baari.queue.IssuedToken;
import com.baari.queue.JoinRequest;
import com.baari.queue.QueueDayService;
import com.baari.queue.TokenActionService;
import com.baari.queue.TokenService;
import com.baari.queue.VisitType;
import com.baari.queue.WalkInRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 4 against real Postgres: which estimator is used, what patients and staff are shown, and
 * the prediction log with its actual waits (PLAN §8, §12 Phase 4). The clock is controlled, the
 * doctor sees patients for exactly as long as the test says, and each test uses a fresh clinic.
 */
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "baari.events.group-prefix=eta-it-"})
class EtaIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-14T04:30:00Z"); // 10:00 in India
    private static final MutableClock CLOCK = MutableClock.at(NOW);
    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    EtaService eta;

    @Autowired
    TokenService tokens;

    @Autowired
    TokenActionService actions;

    @Autowired
    QueueDayService days;

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    PredictionLog predictionLog;

    @Autowired
    JsonMapper json;

    @Autowired
    CalibrationStore calibration;

    StaffPrincipal admin;
    StaffPrincipal receptionist;
    StaffPrincipal doctor;
    UUID queue;

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        ClinicView clinic = clinicAdmin.createClinic(
                PLATFORM,
                new NewClinic(
                        "clinic-" + UUID.randomUUID().toString().substring(0, 8),
                        "Clinic",
                        "Asia/Kolkata",
                        "admin-" + UUID.randomUUID().toString().substring(0, 8),
                        "s3cret-pass"));
        admin = new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, clinic.id(), null);
        receptionist = new StaffPrincipal(UUID.randomUUID(), "rec", StaffRole.RECEPTIONIST, clinic.id(), null);
        UUID doctorId =
                doctorAdmin.create(admin, new DoctorDetails("Dr. Sharma", null)).id();
        doctor = new StaffPrincipal(UUID.randomUUID(), "doc", StaffRole.DOCTOR, clinic.id(), doctorId);
        queue = queueAdmin
                .create(
                        admin,
                        "dr-sharma",
                        new QueueDetails("Dr. Sharma", QueueMode.SINGLE, QrMode.STATIC, "A", Set.of(doctorId)))
                .queue()
                .id();
        days.open(admin, queue);
    }

    /** The prediction log works in the background; tests wait for it before they look at its table. */
    private JdbcClient db() {
        predictionLog.awaitIdle();
        return jdbc;
    }

    private IssuedToken walkIn() {
        IssuedToken token = tokens.issueWalkIn(
                receptionist,
                queue,
                new WalkInRequest("Patient", null, VisitType.NEW, com.baari.queue.Priority.NORMAL, null));
        predictionLog.awaitIdle(); // the JOIN row is written before the next action
        return token;
    }

    private IssuedToken qrJoin() {
        IssuedToken token = tokens.join(queue, new JoinRequest("Asha", null, VisitType.NEW, "v1", UUID.randomUUID()));
        predictionLog.awaitIdle();
        return token;
    }

    /** A whole consultation of the given length, from walk-in to complete. */
    private void consultation(int seconds) {
        IssuedToken token = walkIn();
        actions.callNext(doctor, queue, null);
        actions.start(doctor, token.tokenId());
        CLOCK.advance(Duration.ofSeconds(seconds));
        actions.complete(doctor, token.tokenId());
    }

    /** Actual waits are filled in by a Kafka consumer, a moment after the consultation starts. */
    private void awaitActuals(UUID tokenId) {
        await().atMost(Duration.ofSeconds(30))
                .until(() ->
                        jdbc.sql("SELECT count(*) FROM eta_prediction WHERE token_id = ? AND actual_wait_s IS NULL")
                                        .param(tokenId)
                                        .query(Integer.class)
                                        .single()
                                == 0);
    }

    private List<String> triggers(UUID tokenId) {
        return db().sql("SELECT trigger FROM eta_prediction WHERE token_id = ? ORDER BY trigger")
                .param(tokenId)
                .query(String.class)
                .list();
    }

    private double inRangeCount(boolean hit) {
        predictionLog.awaitIdle();
        Counter counter = meters.find("baari.eta.in_range")
                .tag("hit", Boolean.toString(hit))
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double absErrorTotal(String estimator, String trigger) {
        predictionLog.awaitIdle();
        DistributionSummary summary = meters.find("baari.eta.abs_error.seconds")
                .tag("estimator", estimator)
                .tag("trigger", trigger)
                .summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    // --- which estimator ---

    @Test
    void aClinicWithoutHistoryUsesTheDefaultEstimator() {
        walkIn();

        assertThat(eta.projectQueue(queue).orElseThrow().estimator()).isEqualTo("DEFAULT");
    }

    @Test
    void onceTheClinicHasTwentyFinishedConsultationsRollingIsUsedInstead() {
        for (int i = 0; i < 19; i++) {
            consultation(240);
        }
        assertThat(eta.projectQueue(queue).orElseThrow().estimator()).isEqualTo("DEFAULT");

        consultation(240);
        IssuedToken first = walkIn();
        IssuedToken second = walkIn();

        EtaService.Projected projected = eta.projectQueue(queue).orElseThrow();
        assertThat(projected.estimator()).isEqualTo("ROLLING");
        // Every consultation took 240 s, so the second patient waits one consultation plus the 30 s gap.
        assertThat(projected.byToken().get(first.tokenId()).waitSeconds()).isZero();
        assertThat(projected.byToken().get(second.tokenId()).waitSeconds()).isEqualTo(270);
    }

    @Test
    void consultationsClosedAutomaticallyAtTheEndOfTheDayDoNotCountAsHistory() {
        for (int i = 0; i < 19; i++) {
            consultation(240);
        }
        IssuedToken last = walkIn();
        actions.callNext(doctor, queue, null);
        actions.start(doctor, last.tokenId());
        CLOCK.advance(Duration.ofMinutes(10));
        days.close(admin, queue); // completes it with the AUTO_CLOSED flag

        // 19 real consultations plus one auto-closed: still not enough.
        assertThat(eta.projectQueue(queue).orElseThrow().estimator()).isEqualTo("DEFAULT");
    }

    // --- what patients and staff see ---

    @Test
    void patientsSeeARoundedRangeThatGrowsWithTheirPlaceInLine() {
        IssuedToken first = walkIn();
        IssuedToken second = walkIn();
        IssuedToken third = walkIn();

        // Default estimator: 360 s mean, 30 s gap. Second: wait 390 s, range 228 to 552 s (5 to 10 min).
        // Third: wait 780 s, range 551 to 1009 s (10 to 15 min).
        assertThat(eta.patientView(first.publicCode()).eta())
                .isEqualTo(new EtaDisplay(EtaDisplay.Kind.FEW_MINUTES, null, null));
        assertThat(eta.patientView(second.publicCode()).eta()).isEqualTo(new EtaDisplay(EtaDisplay.Kind.RANGE, 5, 10));
        assertThat(eta.patientView(third.publicCode()).eta()).isEqualTo(new EtaDisplay(EtaDisplay.Kind.RANGE, 10, 15));
    }

    @Test
    void theRangeDoesNotJitterAsTimePassesWithoutRealChange() {
        walkIn();
        walkIn();
        IssuedToken third = walkIn();
        EtaDisplay shown = eta.patientView(third.publicCode()).eta();

        for (int i = 0; i < 6; i++) {
            CLOCK.advance(Duration.ofSeconds(5));
            assertThat(eta.patientView(third.publicCode()).eta()).isEqualTo(shown);
        }
    }

    @Test
    void thePatientPageStaysCorrectWhenSomeoneAheadLeaves() {
        IssuedToken first = walkIn();
        walkIn();
        IssuedToken third = walkIn();
        assertThat(eta.patientView(third.publicCode()).eta()).isEqualTo(new EtaDisplay(EtaDisplay.Kind.RANGE, 10, 15));

        actions.cancel(receptionist, first.tokenId()); // third is now second in line

        assertThat(eta.patientView(third.publicCode()).eta()).isEqualTo(new EtaDisplay(EtaDisplay.Kind.RANGE, 5, 10));
    }

    @Test
    void thereIsNoEstimateOnceThePatientIsNoLongerWaiting() {
        IssuedToken first = walkIn();
        actions.callNext(doctor, queue, null);

        assertThat(eta.patientView(first.publicCode()).eta()).isNull();
    }

    @Test
    void thereIsNoEstimateWhenTheDayIsClosed() {
        IssuedToken first = walkIn();
        days.close(admin, queue);

        assertThat(eta.patientView(first.publicCode()).eta()).isNull();
    }

    @Test
    void anUnknownCodeIsNotFound() {
        assertThatThrownBy(() -> eta.patientView("nope-nope-nope-nope-nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void staffSeeTheExpectedStartOfEveryWaitingPatient() {
        IssuedToken first = walkIn();
        IssuedToken second = walkIn();
        IssuedToken third = walkIn();

        StaffBoardView view = eta.staffBoard(receptionist, queue);

        assertThat(view.board().waiting()).hasSize(3);
        assertThat(view.estimates()).containsOnlyKeys(first.tokenId(), second.tokenId(), third.tokenId());
        assertThat(view.estimates().get(first.tokenId())).isEqualTo(new TokenEstimate(1, NOW, 0));
        assertThat(view.estimates().get(second.tokenId())).isEqualTo(new TokenEstimate(2, NOW.plusSeconds(390), 390));
        assertThat(view.estimates().get(third.tokenId()).waitSeconds()).isEqualTo(780);
    }

    @Test
    void theStaffBoardStillChecksAccess() {
        StaffPrincipal elsewhere =
                new StaffPrincipal(UUID.randomUUID(), "x", StaffRole.RECEPTIONIST, UUID.randomUUID(), null);

        assertThatThrownBy(() -> eta.staffBoard(elsewhere, queue)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theJsonKeepsTheQueueFieldsAtTheTopLevelAndAddsTheEstimate() {
        walkIn();
        IssuedToken second = walkIn();

        JsonNode patient = json.readTree(json.writeValueAsString(eta.patientView(second.publicCode())));
        assertThat(patient.get("displayCode").asString()).isEqualTo(second.displayCode());
        assertThat(patient.get("status").asString()).isEqualTo("WAITING");
        assertThat(patient.get("eta").get("kind").asString()).isEqualTo("RANGE");
        assertThat(patient.get("eta").get("fromMinutes").asInt()).isEqualTo(5);

        JsonNode staff = json.readTree(json.writeValueAsString(eta.staffBoard(receptionist, queue)));
        assertThat(staff.get("waiting")).hasSize(2);
        assertThat(staff.get("estimates")).hasSize(2);
        assertThat(staff.get("estimates")
                        .get(second.tokenId().toString())
                        .get("waitSeconds")
                        .asInt())
                .isEqualTo(390);
    }

    // --- the prediction log ---

    @Test
    void theActualWaitIsFilledInWhenTheConsultationStarts() {
        double missesBefore = inRangeCount(false);
        double errorBefore = absErrorTotal("DEFAULT", "JOIN");
        IssuedToken patient = qrJoin();
        assertThat(triggers(patient.tokenId())).containsExactly("JOIN");
        assertThat(db().sql("SELECT count(*) FROM eta_prediction WHERE token_id = ? AND actual_wait_s IS NULL")
                        .param(patient.tokenId())
                        .query(Integer.class)
                        .single())
                .as("the wait is not known yet")
                .isEqualTo(1);

        CLOCK.advance(Duration.ofMinutes(12));
        actions.callNext(doctor, queue, null);
        actions.start(doctor, patient.tokenId());
        awaitActuals(patient.tokenId());

        Map<String, Object> row = db().sql("""
                        SELECT predicted_at, position, predicted_wait_s, range_min_s, range_max_s, estimator, actual_wait_s
                          FROM eta_prediction WHERE token_id = ?
                        """).param(patient.tokenId()).query().singleRow();
        assertThat(row.get("position")).isEqualTo(1);
        assertThat(row.get("predicted_wait_s")).isEqualTo(0);
        assertThat(row.get("estimator")).isEqualTo("DEFAULT");
        assertThat(row.get("actual_wait_s")).isEqualTo(720);
        // Predicted 0 and waited 720 s: an error of 720 s that missed the range.
        assertThat(inRangeCount(false) - missesBefore).isEqualTo(1);
        assertThat(absErrorTotal("DEFAULT", "JOIN") - errorBefore).isEqualTo(720);
    }

    @Test
    void aPredictionThatWasRightCountsAsInRange() {
        double hitsBefore = inRangeCount(true);
        qrJoin(); // first in line: predicted 0 s
        IssuedToken second = qrJoin(); // predicted 390 s, range 228 to 552 s

        CLOCK.advance(Duration.ofSeconds(400));
        actions.callNext(doctor, queue, null);
        actions.callNext(doctor, queue, null);
        actions.start(doctor, second.tokenId());
        awaitActuals(second.tokenId());

        assertThat(db().sql("SELECT actual_wait_s FROM eta_prediction WHERE token_id = ? AND trigger = 'JOIN'")
                        .param(second.tokenId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(400);
        assertThat(inRangeCount(true) - hitsBefore).isEqualTo(1);
    }

    @Test
    void aPatientSeenAtOnceHasAnActualWaitOfZero() {
        IssuedToken patient = walkIn();
        actions.start(doctor, patient.tokenId()); // direct start, straight from WAITING
        awaitActuals(patient.tokenId());

        assertThat(db().sql("SELECT actual_wait_s FROM eta_prediction WHERE token_id = ?")
                        .param(patient.tokenId())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void positionMilestonesAreLoggedOnceWhenFirstReached() {
        List<IssuedToken> patients = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            patients.add(walkIn());
        }
        IssuedToken twelfth = patients.get(11);
        IssuedToken third = patients.get(2);
        assertThat(triggers(twelfth.tokenId())).containsExactly("JOIN");

        actions.callNext(doctor, queue, null); // twelfth is 11th
        assertThat(triggers(twelfth.tokenId())).containsExactly("JOIN");
        actions.callNext(doctor, queue, null); // twelfth is 10th
        assertThat(triggers(twelfth.tokenId())).containsExactly("JOIN", "POSITION_10");
        for (int i = 0; i < 5; i++) {
            actions.callNext(doctor, queue, null);
        }
        // twelfth is 5th; the third patient (joined at position 3) is first in line since patient 2 was called
        assertThat(triggers(twelfth.tokenId())).containsExactly("JOIN", "POSITION_10", "POSITION_5");
        // Moving up without reaching the next milestone (5th to 4th) logs nothing new.
        actions.cancel(receptionist, patients.get(10).tokenId());
        assertThat(triggers(twelfth.tokenId())).containsExactly("JOIN", "POSITION_10", "POSITION_5");
        // The patient who joined third never "reaches" 10, 5 or 3, only 1.
        assertThat(triggers(third.tokenId())).containsExactly("JOIN", "POSITION_1");
    }

    @Test
    void thePredictionsOfAJoinAreLoggedWithTheRangeAndTheEstimator() {
        walkIn();
        walkIn();
        IssuedToken third = qrJoin();

        Map<String, Object> row = db().sql(
                        "SELECT position, predicted_wait_s, range_min_s, range_max_s, estimator FROM eta_prediction WHERE token_id = ?")
                .param(third.tokenId())
                .query()
                .singleRow();

        assertThat(row.get("position")).isEqualTo(3);
        assertThat(row.get("predicted_wait_s")).isEqualTo(780);
        assertThat(row.get("range_min_s")).isEqualTo(551);
        assertThat(row.get("range_max_s")).isEqualTo(1009);
        assertThat(row.get("estimator")).isEqualTo("DEFAULT");
    }

    // ---- range calibration (PLAN 8.5, ADR 0012) ----

    /** 15 waiting patients with 4 scored predictions each: 60 predictions, {@code covered} of them hit. */
    private void scoredPredictions(int covered) {
        List<UUID> waiting = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            waiting.add(walkIn().tokenId());
        }
        String[] triggers = {"POSITION_10", "POSITION_5", "POSITION_3", "POSITION_1"};
        int n = 0;
        for (UUID tokenId : waiting) {
            for (String trigger : triggers) {
                int actual = n++ < covered ? 600 : 2000; // range is 500 to 700
                db().sql("""
                                INSERT INTO eta_prediction (id, token_id, clinic_id, predicted_at, trigger, position,
                                    predicted_wait_s, range_min_s, range_max_s, estimator, actual_wait_s)
                                VALUES (?, ?, ?, ?, ?, 1, 600, 500, 700, 'DEFAULT', ?)
                                """)
                        .params(
                                UUID.randomUUID(),
                                tokenId,
                                admin.clinicId(),
                                java.time.OffsetDateTime.ofInstant(CLOCK.instant(), java.time.ZoneOffset.UTC),
                                trigger,
                                actual)
                        .update();
            }
        }
    }

    @Test
    void anUncalibratedClinicUsesTheProjectorsRangeUnchanged() {
        assertThat(calibration.factor(admin.clinicId())).isEqualTo(1.0);
    }

    @Test
    void nightlyCalibrationWidensTooNarrowRangesOneStepPerDay() {
        scoredPredictions(24); // 40% covered, below the 60% target

        calibration.recalibrate();
        assertThat(calibration.factor(admin.clinicId())).isEqualTo(1.1);

        calibration.recalibrate(); // a second instance, or a rerun, on the same day changes nothing
        assertThat(calibration.factor(admin.clinicId())).isEqualTo(1.1);

        CLOCK.advance(Duration.ofDays(1));
        calibration.recalibrate();
        assertThat(calibration.factor(admin.clinicId())).isEqualTo(1.2);
    }

    @Test
    void rangesNarrowWhenTheyHoldTooManyActualWaitsButNeverBelowOne() {
        scoredPredictions(54); // 90% covered
        calibration.recalibrate();
        assertThat(calibration.factor(admin.clinicId())).isEqualTo(1.0);
    }

    @Test
    void theCalibrationFactorWidensTheRangesPatientsAreShown() {
        scoredPredictions(24);
        QueueProjector.Projection before = eta.projectQueue(queue).orElseThrow().byToken().values().stream()
                .filter(p -> p.position() == 5)
                .findFirst()
                .orElseThrow();

        calibration.recalibrate(); // factor 1.1
        CLOCK.advance(Duration.ofMinutes(2)); // past the one-minute estimator cache and the projection cache

        QueueProjector.Projection after = eta.projectQueue(queue).orElseThrow().byToken().values().stream()
                .filter(p -> p.position() == 5)
                .findFirst()
                .orElseThrow();
        double widthBefore = before.rangeMaxSeconds() - before.rangeMinSeconds();
        double widthAfter = after.rangeMaxSeconds() - after.rangeMinSeconds();
        assertThat(widthAfter / widthBefore).isCloseTo(1.1, org.assertj.core.data.Offset.offset(0.02));
    }
}
