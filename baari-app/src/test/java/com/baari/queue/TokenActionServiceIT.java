package com.baari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.baari.common.InvalidRequestException;
import com.baari.common.MutableClock;
import com.baari.common.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Staff and patient actions on tokens against real Postgres (PLAN §4, §6.2, §6.3), including the
 * concurrency tests 4 and 5 of §6.4, each repeated 5 times. Not {@code @Transactional}: the
 * service's own transactions must commit. Each test uses a fresh clinic.
 *
 * <p>Settings are the PLAN §14 defaults: at most 2 PRIORITY in a row, recall goes behind 2 NORMAL
 * patients, 3 minutes no-show grace.
 */
class TokenActionServiceIT extends AbstractIntegrationTest {

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
    TokenActionService actions;

    @Autowired
    TokenService tokens;

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

    StaffPrincipal admin;
    StaffPrincipal receptionist;
    StaffPrincipal doctor1;
    StaffPrincipal doctor2;
    UUID pool; // served by both doctors
    UUID single; // served by doctor 1 only

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ClinicView newClinic() {
        return clinicAdmin.createClinic(
                PLATFORM, new NewClinic(unique("clinic"), "Clinic", "Asia/Kolkata", unique("admin"), "s3cret-pass"));
    }

    private UUID queue(String slug, QueueMode mode, String prefix, Set<UUID> doctorIds) {
        return queueAdmin
                .create(admin, slug, new QueueDetails(slug, mode, QrMode.STATIC, prefix, doctorIds))
                .queue()
                .id();
    }

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        ClinicView clinic = newClinic();
        admin = new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, clinic.id(), null);
        receptionist = new StaffPrincipal(UUID.randomUUID(), "rec", StaffRole.RECEPTIONIST, clinic.id(), null);
        UUID d1 =
                doctorAdmin.create(admin, new DoctorDetails("Dr. Sharma", null)).id();
        UUID d2 = doctorAdmin.create(admin, new DoctorDetails("Dr. Gill", null)).id();
        doctor1 = new StaffPrincipal(UUID.randomUUID(), "d1", StaffRole.DOCTOR, clinic.id(), d1);
        doctor2 = new StaffPrincipal(UUID.randomUUID(), "d2", StaffRole.DOCTOR, clinic.id(), d2);
        pool = queue("general", QueueMode.POOL, "B", Set.of(d1, d2));
        single = queue("dr-sharma", QueueMode.SINGLE, "A", Set.of(d1));
        days.open(admin, pool);
        days.open(admin, single);
    }

    private IssuedToken walkIn(UUID queueId, Priority priority) {
        return tokens.issueWalkIn(
                receptionist, queueId, new WalkInRequest("Patient", null, VisitType.NEW, priority, null));
    }

    private Map<String, Object> row(UUID tokenId) {
        return jdbc.sql(
                        "SELECT status, flags::text AS flags, served_by_doctor_id, called_at, consult_started_at, completed_at, cancelled_at, sort_key FROM token WHERE id = ?")
                .param(tokenId)
                .query()
                .singleRow();
    }

    private List<String> eventTypes(UUID tokenId) {
        return jdbc.sql("SELECT type FROM token_event WHERE token_id = ? ORDER BY seq")
                .param(tokenId)
                .query(String.class)
                .list();
    }

    private List<String> outboxTypes(UUID queueId) {
        UUID dayId = days.today(admin, queueId).orElseThrow().id();
        return jdbc.sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at, event_type")
                .param(dayId)
                .query(String.class)
                .list();
    }

    @Nested
    class CallNext {

        @Test
        void followsThePolicyOrderAndRemembersThePriorityCounter() {
            String n1 = walkIn(pool, Priority.NORMAL).displayCode();
            String n2 = walkIn(pool, Priority.NORMAL).displayCode();
            String p1 = walkIn(pool, Priority.PRIORITY).displayCode();
            String p2 = walkIn(pool, Priority.PRIORITY).displayCode();
            String p3 = walkIn(pool, Priority.PRIORITY).displayCode();

            List<String> called = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                called.add(actions.callNext(doctor1, pool, null).displayCode());
            }

            // Two PRIORITY in a row, then a NORMAL gets a turn (max-consecutive-priority = 2).
            assertThat(called).containsExactly(p1, p2, n1, p3, n2);
            assertThatThrownBy(() -> actions.callNext(doctor1, pool, null)).isInstanceOf(NoOneWaitingException.class);
            // The last call was a NORMAL, which reset the counter stored on the day; one more
            // PRIORITY call takes it to 1.
            walkIn(pool, Priority.PRIORITY);
            actions.callNext(doctor1, pool, null);
            assertThat(jdbc.sql("SELECT consecutive_priority_served FROM queue_day WHERE queue_id = ?")
                            .param(pool)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }

        @Test
        void recordsCallTimeDoctorEventAndOutbox() {
            IssuedToken issued = walkIn(pool, Priority.NORMAL);
            CLOCK.advance(Duration.ofMinutes(10));

            StaffTokenView called = actions.callNext(receptionist, pool, doctor2.doctorId());

            assertThat(called.status()).isEqualTo(TokenStatus.CALLED);
            assertThat(called.servedByDoctorId()).isEqualTo(doctor2.doctorId());
            assertThat(called.calledAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
            assertThat(called.flags()).isEmpty();
            assertThat(called.patientName()).isEqualTo("Patient");
            assertThat(eventTypes(issued.tokenId())).containsExactly("JOINED", "CALLED");
            assertThat(outboxTypes(pool)).contains("TokenCalled");
        }

        @Test
        void aPoolQueueNeedsToKnowWhichDoctorIsCalling() {
            walkIn(pool, Priority.NORMAL);

            assertThatThrownBy(() -> actions.callNext(receptionist, pool, null))
                    .isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> actions.callNext(doctor1, pool, doctor2.doctorId()))
                    .isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> actions.callNext(receptionist, pool, UUID.randomUUID()))
                    .isInstanceOf(InvalidRequestException.class);
            assertThat(actions.callNext(doctor2, pool, null).servedByDoctorId()).isEqualTo(doctor2.doctorId());
        }

        @Test
        void aSingleQueueUsesItsDoctorAndOtherDoctorsCannotTouchIt() {
            walkIn(single, Priority.NORMAL);

            assertThatThrownBy(() -> actions.callNext(doctor2, single, null)).isInstanceOf(NotFoundException.class);
            assertThat(actions.callNext(receptionist, single, null).servedByDoctorId())
                    .isEqualTo(doctor1.doctorId());
        }

        @Test
        void worksWhilePausedButNotOnAClosedDay() {
            walkIn(pool, Priority.NORMAL);
            walkIn(pool, Priority.NORMAL);
            days.pause(admin, pool);

            assertThat(actions.callNext(doctor1, pool, null).status()).isEqualTo(TokenStatus.CALLED);

            days.close(admin, pool);
            assertThatThrownBy(() -> actions.callNext(doctor1, pool, null)).isInstanceOf(QueueClosedException.class);
        }
    }

    @Nested
    class Consultation {

        @Test
        void callStartCompleteRecordsEveryTimestamp() {
            UUID id = walkIn(single, Priority.NORMAL).tokenId();
            actions.callNext(doctor1, single, null);
            CLOCK.advance(Duration.ofMinutes(1));
            actions.start(doctor1, id);
            CLOCK.advance(Duration.ofMinutes(6));

            StaffTokenView done = actions.complete(doctor1, id);

            assertThat(done.status()).isEqualTo(TokenStatus.COMPLETED);
            assertThat(done.calledAt()).isEqualTo(NOW);
            assertThat(done.consultStartedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
            assertThat(done.completedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(7)));
            assertThat(done.servedByDoctorId()).isEqualTo(doctor1.doctorId());
            assertThat(eventTypes(id)).containsExactly("JOINED", "CALLED", "CONSULT_STARTED", "COMPLETED");
            assertThat(outboxTypes(single)).contains("ConsultStarted", "ConsultCompleted");
        }

        @Test
        void startingAWaitingTokenIsADirectStart() {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();

            StaffTokenView started = actions.start(doctor2, id);

            assertThat(started.status()).isEqualTo(TokenStatus.IN_CONSULTATION);
            assertThat(started.flags()).containsExactly(TokenFlag.DIRECT_START);
            assertThat(started.servedByDoctorId()).isEqualTo(doctor2.doctorId());
            assertThat(started.calledAt()).isNull();
        }

        @Test
        void actionsTheStatusDoesNotAllowAreRefused() {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();

            assertThatThrownBy(() -> actions.complete(doctor1, id))
                    .isInstanceOf(IllegalTransitionException.class)
                    .hasMessageContaining("WAITING");
            assertThatThrownBy(() -> actions.recall(doctor1, id)).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> actions.noShow(doctor1, id, true)).isInstanceOf(IllegalTransitionException.class);
            assertThat(row(id)).containsEntry("status", "WAITING");
            assertThat(eventTypes(id)).containsExactly("JOINED");
        }

        @Test
        void overrideCallPicksAPatientOutOfOrder() {
            walkIn(pool, Priority.NORMAL);
            UUID second = walkIn(pool, Priority.NORMAL).tokenId();

            StaffTokenView called = actions.call(receptionist, second, doctor1.doctorId());

            assertThat(called.status()).isEqualTo(TokenStatus.CALLED);
            assertThat(called.flags()).containsExactly(TokenFlag.OVERRIDE_CALL);
            assertThat(called.servedByDoctorId()).isEqualTo(doctor1.doctorId());
        }
    }

    @Nested
    class NoShowAndRecall {

        @Test
        void noShowWaitsForTheGracePeriodUnlessForced() {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            actions.callNext(doctor1, pool, null);
            CLOCK.advance(Duration.ofMinutes(2));

            assertThatThrownBy(() -> actions.noShow(doctor1, id, false))
                    .isInstanceOf(GraceNotElapsedException.class)
                    .hasMessageContaining("3 minutes");

            CLOCK.advance(Duration.ofMinutes(1)); // exactly 3 minutes: grace has passed
            StaffTokenView noShow = actions.noShow(doctor1, id, false);
            assertThat(noShow.status()).isEqualTo(TokenStatus.NO_SHOW);
            assertThat(noShow.flags()).isEmpty();
        }

        @Test
        void aForcedEarlyNoShowIsFlagged() {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            actions.callNext(doctor1, pool, null);

            assertThat(actions.noShow(doctor1, id, true).flags()).containsExactly(TokenFlag.FORCED_NO_SHOW);
            assertThat(outboxTypes(pool)).contains("TokenNoShow");
        }

        @Test
        void aRecalledNoShowGoesBackBehindTwoNormalPatients() {
            UUID n1 = walkIn(pool, Priority.NORMAL).tokenId();
            String n2 = walkIn(pool, Priority.NORMAL).displayCode();
            String n3 = walkIn(pool, Priority.NORMAL).displayCode();
            String n4 = walkIn(pool, Priority.NORMAL).displayCode();
            actions.callNext(doctor1, pool, null); // n1
            actions.noShow(doctor1, n1, true);

            StaffTokenView recalled = actions.recall(receptionist, n1);

            assertThat(recalled.status()).isEqualTo(TokenStatus.WAITING);
            assertThat(recalled.calledAt()).isNull();
            assertThat(recalled.servedByDoctorId()).isNull();
            assertThat(row(n1)).containsEntry("sort_key", 3001L); // just behind n3 (key 3000)
            List<String> order = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                order.add(actions.callNext(doctor1, pool, null).displayCode());
            }
            assertThat(order).containsExactly(n2, n3, recalled.displayCode(), n4);
            assertThat(eventTypes(n1)).containsExactly("JOINED", "CALLED", "NO_SHOW", "RECALLED", "CALLED");
        }

        @Test
        void aNoShowCanOnlyBeRecalledTheSameDay() {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            actions.callNext(doctor1, pool, null);
            actions.noShow(doctor1, id, true);
            CLOCK.advance(Duration.ofDays(1));

            assertThatThrownBy(() -> actions.recall(receptionist, id))
                    .isInstanceOf(IllegalTransitionException.class)
                    .hasMessageContaining("same day");
        }
    }

    @Nested
    class CancelAndPriority {

        @Test
        void staffCanCancelANoShow() {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            actions.callNext(doctor1, pool, null);
            actions.noShow(doctor1, id, true);

            StaffTokenView cancelled = actions.cancel(receptionist, id);

            assertThat(cancelled.status()).isEqualTo(TokenStatus.CANCELLED);
            assertThat(cancelled.cancelledAt()).isEqualTo(NOW);
        }

        @Test
        void aPatientCancelsWithTheirPublicCode() {
            IssuedToken issued = walkIn(pool, Priority.NORMAL);

            assertThat(actions.cancelByPatient(issued.publicCode())).isEqualTo(TokenStatus.CANCELLED);
            assertThat(jdbc.sql("SELECT actor_type FROM token_event WHERE token_id = ? AND type = 'CANCELLED'")
                            .param(issued.tokenId())
                            .query(String.class)
                            .single())
                    .isEqualTo("PATIENT");
            assertThat(outboxTypes(pool)).contains("TokenCancelled");
            // Already cancelled: nothing more to do.
            assertThatThrownBy(() -> actions.cancelByPatient(issued.publicCode()))
                    .isInstanceOf(IllegalTransitionException.class);
        }

        @Test
        void aPatientCannotCancelANoShowAndUnknownCodesAreNotFound() {
            IssuedToken issued = walkIn(pool, Priority.NORMAL);
            actions.callNext(doctor1, pool, null);
            actions.noShow(doctor1, issued.tokenId(), true);

            assertThatThrownBy(() -> actions.cancelByPatient(issued.publicCode()))
                    .isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> actions.cancelByPatient("0000000000000000000000"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void raisingThePriorityMovesThePatientAhead() {
            walkIn(pool, Priority.NORMAL);
            UUID second = walkIn(pool, Priority.NORMAL).tokenId();

            assertThat(actions.setPriority(receptionist, second, Priority.EMERGENCY)
                            .priority())
                    .isEqualTo(Priority.EMERGENCY);
            actions.setPriority(receptionist, second, Priority.EMERGENCY); // same again: no new event

            assertThat(actions.callNext(doctor1, pool, null).id()).isEqualTo(second);
            assertThat(eventTypes(second)).containsExactly("JOINED", "PRIORITY_CHANGED", "CALLED");
            assertThatThrownBy(() -> actions.setPriority(receptionist, second, Priority.NORMAL))
                    .isInstanceOf(IllegalTransitionException.class);
        }
    }

    @Nested
    class Access {

        @Test
        void anotherClinicsTokenIsNotFound() {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            StaffPrincipal otherAdmin = new StaffPrincipal(
                    UUID.randomUUID(), "x", StaffRole.CLINIC_ADMIN, newClinic().id(), null);

            assertThatThrownBy(() -> actions.start(otherAdmin, id))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Token");
            assertThatThrownBy(() -> actions.cancel(otherAdmin, id)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> actions.start(admin, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
            assertThat(row(id)).containsEntry("status", "WAITING");
        }

        @Test
        void aDoctorCannotActOnTokensOfAQueueTheyAreNotOn() {
            UUID id = walkIn(single, Priority.NORMAL).tokenId();

            assertThatThrownBy(() -> actions.start(doctor2, id)).isInstanceOf(NotFoundException.class);
            assertThat(actions.start(doctor1, id).status()).isEqualTo(TokenStatus.IN_CONSULTATION);
        }
    }

    /** PLAN §6.4 concurrency tests 4 and 5. */
    @Nested
    class Concurrency {

        /** Test 4: two doctors call-next at once with one patient waiting: never both get them. */
        @RepeatedTest(5)
        void twoDoctorsCallingAtOnceNeverGetTheSamePatient() throws Exception {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            List<StaffPrincipal> doctors = List.of(doctor1, doctor2);

            var outcomes = Race.run(2, i -> () -> actions.callNext(doctors.get(i), pool, null));

            assertThat(outcomes).filteredOn(Race.Outcome::succeeded).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(o -> !o.succeeded())
                    .singleElement()
                    .satisfies(o -> assertThat(o.error()).isInstanceOf(NoOneWaitingException.class));
            assertThat(eventTypes(id)).containsExactly("JOINED", "CALLED");
            assertThat(row(id)).containsEntry("status", "CALLED");
        }

        /**
         * Test 5: two conflicting actions on one token at once: exactly one wins, the other gets a
         * 409 (illegal transition, or a version conflict). PLAN's example pairs complete with
         * no-show, but the state machine never allows both from the same status; start and
         * no-show (both allowed from CALLED) make the race real.
         */
        @RepeatedTest(5)
        void startAndNoShowAtOnceOnlyOneWins() throws Exception {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            actions.callNext(doctor1, pool, null);

            var outcomes = Race.run(
                    2, i -> i == 0 ? () -> actions.start(doctor1, id) : () -> actions.noShow(receptionist, id, true));

            assertThat(outcomes).filteredOn(Race.Outcome::succeeded).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(o -> !o.succeeded())
                    .singleElement()
                    .satisfies(o -> assertThat(o.error())
                            .isInstanceOfAny(
                                    IllegalTransitionException.class, OptimisticLockingFailureException.class));
            String winner = outcomes.get(0).succeeded() ? "IN_CONSULTATION" : "NO_SHOW";
            assertThat(row(id)).containsEntry("status", winner);
            assertThat(eventTypes(id)).hasSize(3); // JOINED, CALLED, and the winner's event only
        }

        /** Test 5, double-click: two completes on one consultation; the second gets a 409. */
        @RepeatedTest(5)
        void aDoubleClickedCompleteCompletesOnce() throws Exception {
            UUID id = walkIn(pool, Priority.NORMAL).tokenId();
            actions.callNext(doctor1, pool, null);
            actions.start(doctor1, id);

            var outcomes = Race.run(2, i -> () -> actions.complete(doctor1, id));

            assertThat(outcomes).filteredOn(Race.Outcome::succeeded).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(o -> !o.succeeded())
                    .singleElement()
                    .satisfies(o -> assertThat(o.error())
                            .isInstanceOfAny(
                                    IllegalTransitionException.class, OptimisticLockingFailureException.class));
            assertThat(eventTypes(id)).containsExactly("JOINED", "CALLED", "CONSULT_STARTED", "COMPLETED");
        }
    }
}
