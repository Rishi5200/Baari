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
import com.baari.common.MutableClock;
import com.baari.common.NotFoundException;
import com.baari.common.crypto.InvalidPhoneNumberException;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Token issuance against real Postgres (PLAN §6.1), including the concurrency tests 1, 2, 3 and 6
 * of §6.4 with real threads, each repeated 5 times. Not {@code @Transactional}: the service's own
 * transactions must commit for the races to be real. Each test uses a fresh clinic and queue.
 *
 * <p>The open-token limit is set to 200 here, so concurrency test 1 fills the queue exactly and
 * then checks that one more join is refused.
 */
@TestPropertySource(properties = "baari.queue.max-open-tokens=200")
class TokenServiceIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-14T04:30:00Z"); // 10:00 in India
    private static final MutableClock CLOCK = MutableClock.at(NOW);
    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);
    private static final String PHONE = "98765 43210";

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
    UUID queueId;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ClinicView newClinic() {
        return clinicAdmin.createClinic(
                PLATFORM, new NewClinic(unique("clinic"), "Clinic", "Asia/Kolkata", unique("admin"), "s3cret-pass"));
    }

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        ClinicView clinic = newClinic();
        admin = new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, clinic.id(), null);
        receptionist = new StaffPrincipal(UUID.randomUUID(), "rec", StaffRole.RECEPTIONIST, clinic.id(), null);
        UUID doctorId =
                doctorAdmin.create(admin, new DoctorDetails("Dr. Sharma", null)).id();
        queueId = queueAdmin
                .create(
                        admin,
                        "general",
                        new QueueDetails("General", QueueMode.POOL, QrMode.STATIC, "A", Set.of(doctorId)))
                .queue()
                .id();
    }

    // --- request builders and row readers ---

    private static JoinRequest join(String name, String phone) {
        return new JoinRequest(name, phone, VisitType.NEW, "v1", UUID.randomUUID());
    }

    private static JoinRequest joinWithKey(String phone, UUID idempotencyKey) {
        return new JoinRequest("Patient", phone, VisitType.NEW, "v1", idempotencyKey);
    }

    private static WalkInRequest walkIn(Priority priority) {
        return new WalkInRequest("Walk-in", null, VisitType.FOLLOW_UP, priority, null);
    }

    /** A valid, distinct Indian mobile number for each i. */
    private static String phone(int i) {
        return "98%08d".formatted(i);
    }

    private UUID dayId() {
        return days.today(admin, queueId).orElseThrow().id();
    }

    private Map<String, Object> tokenRow(UUID tokenId) {
        return jdbc.sql("""
                        SELECT number, sort_key, status, source, patient_name, phone_enc, phone_hash, phone_last4,
                               consent_version, visit_type, priority
                          FROM token WHERE id = ?
                        """).param(tokenId).query().singleRow();
    }

    private List<Integer> numbers(UUID dayId) {
        return jdbc.sql("SELECT number FROM token WHERE queue_day_id = ? ORDER BY number")
                .param(dayId)
                .query(Integer.class)
                .list();
    }

    private List<Long> eventSeqs(UUID dayId) {
        return jdbc.sql("SELECT seq FROM token_event WHERE queue_day_id = ? ORDER BY seq")
                .param(dayId)
                .query(Long.class)
                .list();
    }

    private static List<Integer> oneTo(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            list.add(i);
        }
        return list;
    }

    private static List<Long> oneToLong(int n) {
        return oneTo(n).stream().map(Integer::longValue).toList();
    }

    private static <T> List<Race.Outcome<T>> race(int n, IntFunction<Callable<T>> call) throws InterruptedException {
        return Race.run(n, call);
    }

    @Nested
    class Join {

        @BeforeEach
        void openDay() {
            days.open(admin, queueId);
        }

        @Test
        void issuesAWaitingTokenAndProtectsThePhoneNumber() {
            IssuedToken issued = tokens.join(queueId, join("  Asha Verma ", PHONE));

            assertThat(issued.created()).isTrue();
            assertThat(issued.displayCode()).isEqualTo("A-001");
            assertThat(issued.status()).isEqualTo(TokenStatus.WAITING);
            assertThat(issued.priority()).isEqualTo(Priority.NORMAL);
            assertThat(issued.position()).isEqualTo(1);
            assertThat(issued.publicCode()).matches("[0-9A-Za-z]{22}");

            Map<String, Object> row = tokenRow(issued.tokenId());
            assertThat(row)
                    .containsEntry("number", 1)
                    .containsEntry("sort_key", 1000L)
                    .containsEntry("status", "WAITING")
                    .containsEntry("source", "QR")
                    .containsEntry("patient_name", "Asha Verma")
                    .containsEntry("phone_last4", "3210")
                    .containsEntry("consent_version", "v1");
            // Stored encrypted, never in clear; the hash is the 32-byte HMAC.
            String stored = new String((byte[]) row.get("phone_enc"), StandardCharsets.ISO_8859_1);
            assertThat(stored).doesNotContain("9876543210");
            assertThat((byte[]) row.get("phone_hash")).hasSize(32);
        }

        @Test
        void recordsTheJoinedEventWithoutPersonalData() {
            IssuedToken issued = tokens.join(queueId, join("Asha Verma", PHONE));
            UUID dayId = dayId();

            Map<String, Object> event = jdbc.sql(
                            "SELECT seq, type, actor_type, actor_id, data::text AS data FROM token_event WHERE token_id = ?")
                    .param(issued.tokenId())
                    .query()
                    .singleRow();
            assertThat(event)
                    .containsEntry("seq", 2L) // 1 was DAY_OPENED
                    .containsEntry("type", "JOINED")
                    .containsEntry("actor_type", "PATIENT");
            assertThat(event.get("actor_id")).isNull();

            String payload = jdbc.sql(
                            "SELECT payload::text FROM outbox_event WHERE aggregate_id = ? AND event_type = 'TokenJoined'")
                    .param(dayId)
                    .query(String.class)
                    .single();
            assertThat(payload)
                    .contains(issued.tokenId().toString())
                    .contains("\"seq\": 2")
                    .contains("\"source\": \"QR\"")
                    .doesNotContain("Asha")
                    .doesNotContain("3210")
                    .doesNotContain(issued.publicCode());
            assertThat((String) event.get("data")).doesNotContain("Asha").doesNotContain("3210");
        }

        @Test
        void positionsFollowTheCallOrder() {
            assertThat(tokens.join(queueId, join("N1", phone(1))).position()).isEqualTo(1);
            assertThat(tokens.join(queueId, join("N2", phone(2))).position()).isEqualTo(2);
            // A PRIORITY walk-in goes ahead of the waiting NORMAL patients; an EMERGENCY ahead of all.
            IssuedToken priority = tokens.issueWalkIn(receptionist, queueId, walkIn(Priority.PRIORITY));
            assertThat(priority.position()).isEqualTo(1);
            assertThat(priority.displayCode()).isEqualTo("A-003");
            assertThat(tokens.issueWalkIn(receptionist, queueId, walkIn(Priority.EMERGENCY))
                            .position())
                    .isEqualTo(1);
        }

        @Test
        void aRetryReturnsTheSameTokenWithoutUsingANumber() {
            UUID key = UUID.randomUUID();
            IssuedToken first = tokens.join(queueId, joinWithKey(PHONE, key));
            IssuedToken retry = tokens.join(queueId, joinWithKey(PHONE, key));

            assertThat(retry.created()).isFalse();
            assertThat(retry.tokenId()).isEqualTo(first.tokenId());
            assertThat(retry.publicCode()).isEqualTo(first.publicCode());
            assertThat(tokens.join(queueId, join("Next", phone(7))).displayCode())
                    .isEqualTo("A-002");
        }

        @Test
        void aPhoneHoldsOneActiveTokenWhateverTheFormat() {
            IssuedToken first = tokens.join(queueId, join("Asha", PHONE));

            assertThatThrownBy(() -> tokens.join(queueId, join("Asha again", "+91 98765-43210")))
                    .isInstanceOf(AlreadyInQueueException.class)
                    .hasMessageContaining("A-001")
                    .hasMessageNotContaining(first.publicCode())
                    .extracting(e -> ((AlreadyInQueueException) e).displayCode())
                    .isEqualTo("A-001");
            // Reception can't add a second one for the same phone either.
            assertThatThrownBy(() -> tokens.issueWalkIn(
                            receptionist,
                            queueId,
                            new WalkInRequest("Asha", "09876543210", VisitType.NEW, Priority.NORMAL, null)))
                    .isInstanceOf(AlreadyInQueueException.class);
            assertThat(numbers(dayId())).containsExactly(1);
        }

        @Test
        void aPhoneCanJoinAgainOnceItsTokenIsFinished() {
            IssuedToken first = tokens.join(queueId, join("Asha", PHONE));
            jdbc.sql("UPDATE token SET status = 'COMPLETED' WHERE id = ?")
                    .param(first.tokenId())
                    .update();

            assertThat(tokens.join(queueId, join("Asha", PHONE)).displayCode()).isEqualTo("A-002");
        }

        @Test
        void tokensWithoutAPhoneDoNotBlockEachOther() {
            tokens.join(queueId, join("No phone 1", null));
            tokens.join(queueId, join("No phone 2", " "));

            assertThat(numbers(dayId())).containsExactly(1, 2);
        }

        @Test
        void walkInIsIssuedByStaffWithTheChosenPriority() {
            IssuedToken issued = tokens.issueWalkIn(receptionist, queueId, walkIn(Priority.PRIORITY));

            assertThat(tokenRow(issued.tokenId()))
                    .containsEntry("source", "RECEPTION")
                    .containsEntry("priority", "PRIORITY")
                    .containsEntry("visit_type", "FOLLOW_UP");
            assertThat(tokenRow(issued.tokenId()).get("consent_version")).isNull();
            assertThat(jdbc.sql("SELECT actor_type || ':' || actor_id FROM token_event WHERE token_id = ?")
                            .param(issued.tokenId())
                            .query(String.class)
                            .single())
                    .isEqualTo("STAFF:" + receptionist.userId());
        }

        @Test
        void badInputIsRejectedBeforeAnythingIsWritten() {
            assertThatThrownBy(() -> tokens.join(queueId, join("Asha", "12345")))
                    .isInstanceOf(InvalidPhoneNumberException.class);
            assertThatThrownBy(() -> tokens.join(queueId, join(" ", PHONE)))
                    .isInstanceOf(ConstraintViolationException.class);
            assertThatThrownBy(() -> tokens.join(queueId, new JoinRequest("Asha", null, VisitType.NEW, "v1", null)))
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(numbers(dayId())).isEmpty();
        }

        @Test
        void otherClinicsCannotAddWalkIns() {
            StaffPrincipal otherAdmin = new StaffPrincipal(
                    UUID.randomUUID(), "x", StaffRole.CLINIC_ADMIN, newClinic().id(), null);

            assertThatThrownBy(() -> tokens.issueWalkIn(otherAdmin, queueId, walkIn(Priority.NORMAL)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void onePhoneMayJoinAQueueAtMostThreeTimesADay() {
            for (int i = 0; i < 3; i++) {
                IssuedToken t = tokens.join(queueId, join("Asha", PHONE));
                actions.cancelByPatient(t.publicCode()); // cancel and re-join, again and again
            }

            assertThatThrownBy(() -> tokens.join(queueId, join("Asha", PHONE)))
                    .isInstanceOf(TooManyJoinsException.class)
                    .hasMessageContaining("too many times today");
            // reception can still help in person, and another phone is unaffected
            assertThat(tokens.issueWalkIn(admin, queueId, walkIn(Priority.NORMAL))
                            .created())
                    .isTrue();
            assertThat(tokens.join(queueId, join("Ravi", phone(7))).created()).isTrue();
        }
    }

    @Nested
    class DayStatus {

        @Test
        void unknownQueueIsNotFound() {
            assertThatThrownBy(() -> tokens.join(UUID.randomUUID(), join("Asha", PHONE)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void nobodyCanJoinBeforeTheDayIsOpened() {
            assertThatThrownBy(() -> tokens.join(queueId, join("Asha", PHONE)))
                    .isInstanceOf(QueueNotOpenedException.class);
            assertThatThrownBy(() -> tokens.issueWalkIn(receptionist, queueId, walkIn(Priority.NORMAL)))
                    .isInstanceOf(QueueNotOpenedException.class);
        }

        @Test
        void aPausedDayRefusesQrJoinsButReceptionCanStillAddWalkIns() {
            days.open(admin, queueId);
            days.pause(admin, queueId);

            assertThatThrownBy(() -> tokens.join(queueId, join("Asha", PHONE)))
                    .isInstanceOf(QueuePausedException.class);
            assertThat(tokens.issueWalkIn(receptionist, queueId, walkIn(Priority.NORMAL))
                            .displayCode())
                    .isEqualTo("A-001");
        }

        @Test
        void aClosedDayRefusesJoinsButARetryStillGetsItsToken() {
            days.open(admin, queueId);
            UUID key = UUID.randomUUID();
            IssuedToken issued = tokens.join(queueId, joinWithKey(PHONE, key));
            days.close(admin, queueId);

            assertThatThrownBy(() -> tokens.join(queueId, join("Late", phone(9))))
                    .isInstanceOf(QueueClosedException.class);
            assertThatThrownBy(() -> tokens.issueWalkIn(receptionist, queueId, walkIn(Priority.NORMAL)))
                    .isInstanceOf(QueueClosedException.class);
            IssuedToken retry = tokens.join(queueId, joinWithKey(PHONE, key));
            assertThat(retry.tokenId()).isEqualTo(issued.tokenId());
            assertThat(retry.status()).isEqualTo(TokenStatus.EXPIRED);
            assertThat(retry.position()).isNull();
        }
    }

    /** PLAN §6.4 concurrency tests 1, 2, 3 and 6. */
    @Nested
    class Concurrency {

        @BeforeEach
        void openDay() {
            days.open(admin, queueId);
        }

        /** Test 1: 200 joins at once get exactly the numbers 1..200, no duplicates, no gaps. */
        @RepeatedTest(5)
        void twoHundredConcurrentJoinsGetNumbersOneToTwoHundred() throws Exception {
            var outcomes = race(200, i -> () -> tokens.join(queueId, join("P" + i, phone(i))));

            assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
            assertThat(outcomes)
                    .extracting(o -> o.value().displayCode())
                    .doesNotHaveDuplicates()
                    .hasSize(200);
            UUID dayId = dayId();
            assertThat(numbers(dayId)).isEqualTo(oneTo(200));
            assertThat(days.today(admin, queueId).orElseThrow().lastNumber()).isEqualTo(200);
            // DAY_OPENED + 200 JOINED, sequence without gaps.
            assertThat(eventSeqs(dayId)).isEqualTo(oneToLong(201));

            // The limit is 200 open tokens in this test class: the next join is refused.
            assertThatThrownBy(() -> tokens.join(queueId, join("Too many", phone(999))))
                    .isInstanceOf(QueueFullException.class);
            assertThat(numbers(dayId)).hasSize(200);
        }

        /** Test 2: 50 requests with the same idempotency key make exactly one token. */
        @RepeatedTest(5)
        void fiftyRequestsWithTheSameKeyMakeOneToken() throws Exception {
            UUID key = UUID.randomUUID();
            var outcomes = race(50, i -> () -> tokens.join(queueId, joinWithKey(PHONE, key)));

            assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
            assertThat(outcomes)
                    .extracting(o -> o.value().tokenId())
                    .containsOnly(outcomes.getFirst().value().tokenId());
            assertThat(outcomes).filteredOn(o -> o.value().created()).hasSize(1);
            assertThat(numbers(dayId())).containsExactly(1);
        }

        /** Test 3: one phone, different keys, at once: one token, everyone else gets 409. */
        @RepeatedTest(5)
        void samePhoneWithDifferentKeysGetsOneActiveToken() throws Exception {
            var outcomes = race(20, i -> () -> tokens.join(queueId, join("Asha " + i, PHONE)));

            assertThat(outcomes).filteredOn(o -> o.error() == null).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(o -> o.error() != null)
                    .hasSize(19)
                    .allSatisfy(o -> assertThat(o.error()).isInstanceOf(AlreadyInQueueException.class));
            assertThat(numbers(dayId())).containsExactly(1);
        }

        /**
         * Test 6: joins racing with day close. Each join either made a token before the close (now
         * EXPIRED) or was refused because the day was closed. No token is left WAITING in a closed
         * day, and the numbers that were issued have no gaps.
         */
        @RepeatedTest(5)
        void joinsRacingWithCloseNeverLeaveAWaitingTokenInAClosedDay() throws Exception {
            int joins = 30;
            UUID dayId = dayId();
            var outcomes = race(
                    joins + 1,
                    i -> i == joins
                            ? () -> days.close(admin, queueId)
                            : () -> tokens.join(queueId, join("P" + i, phone(i))));

            assertThat(outcomes).allSatisfy(o -> {
                if (o.error() != null) {
                    assertThat(o.error()).isInstanceOf(QueueClosedException.class);
                }
            });
            long issued = outcomes.subList(0, joins).stream()
                    .filter(o -> o.error() == null)
                    .count();
            assertThat(days.today(admin, queueId).orElseThrow().status()).isEqualTo(QueueDayStatus.CLOSED);
            assertThat(jdbc.sql("SELECT status FROM token WHERE queue_day_id = ?")
                            .param(dayId)
                            .query(String.class)
                            .list())
                    .hasSize((int) issued)
                    .allSatisfy(status -> assertThat(status).isEqualTo("EXPIRED"));
            assertThat(numbers(dayId)).isEqualTo(oneTo((int) issued));
        }
    }
}
