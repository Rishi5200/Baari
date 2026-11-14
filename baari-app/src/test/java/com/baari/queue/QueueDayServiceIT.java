package com.baari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.ClinicView;
import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorDetails;
import com.baari.clinic.DoctorView;
import com.baari.clinic.NewClinic;
import com.baari.clinic.QrMode;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.QueueDetails;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.ForbiddenException;
import com.baari.common.MutableClock;
import com.baari.common.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Queue day lifecycle against real Postgres: open (idempotent, also under a race), pause, resume,
 * close (expires open tokens through the state machine), plus the event log and outbox rows each
 * change leaves. Not {@code @Transactional}: the service's own transactions must commit, and each
 * test uses a fresh clinic, so tests don't see each other's rows.
 */
class QueueDayServiceIT extends AbstractIntegrationTest {

    /** 20:00 UTC is 01:30 the next morning in India: the service date must follow the clinic. */
    private static final Instant NOW = Instant.parse("2026-11-14T20:00:00Z");

    private static final LocalDate CLINIC_DATE = LocalDate.parse("2026-11-15");

    private static final MutableClock CLOCK = MutableClock.at(NOW);

    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    QueueDayService service;

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
    DoctorView doctor;
    UUID queueId;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ClinicView newClinic() {
        return clinicAdmin.createClinic(
                PLATFORM, new NewClinic(unique("clinic"), "Clinic", "Asia/Kolkata", unique("admin"), "s3cret-pass"));
    }

    private static StaffPrincipal principal(StaffRole role, UUID clinicId, UUID doctorId) {
        return new StaffPrincipal(UUID.randomUUID(), "u", role, clinicId, doctorId);
    }

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        ClinicView clinic = newClinic();
        admin = principal(StaffRole.CLINIC_ADMIN, clinic.id(), null);
        receptionist = principal(StaffRole.RECEPTIONIST, clinic.id(), null);
        doctor = doctorAdmin.create(admin, new DoctorDetails("Dr. Sharma", "Room 1"));
        queueId = queueAdmin
                .create(
                        admin,
                        "general",
                        new QueueDetails("General", QueueMode.POOL, QrMode.STATIC, "A", Set.of(doctor.id())))
                .queue()
                .id();
    }

    // --- helpers reading the rows the service wrote ---

    private List<Map<String, Object>> events(UUID dayId) {
        return jdbc.sql(
                        "SELECT seq, type, token_id, actor_type, actor_id FROM token_event WHERE queue_day_id = ? ORDER BY seq")
                .param(dayId)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> outbox(UUID dayId) {
        return jdbc.sql(
                        "SELECT event_type, partition_key, payload::text AS payload, published_at FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at, event_type")
                .param(dayId)
                .query()
                .listOfRows();
    }

    private long dayRows() {
        return jdbc.sql("SELECT count(*) FROM queue_day WHERE queue_id = ?")
                .param(queueId)
                .query(Long.class)
                .single();
    }

    private UUID insertToken(UUID dayId, UUID clinicId, int number, TokenStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO token (id, queue_day_id, clinic_id, number, sort_key, public_code, visit_type, status, source, joined_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?, 'RECEPTION', ?)
                        """)
                .params(
                        id,
                        dayId,
                        clinicId,
                        number,
                        number * 1000L,
                        unique("code"),
                        status.name(),
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
        return id;
    }

    private Map<String, Object> token(UUID id) {
        return jdbc.sql("SELECT status, flags::text AS flags, completed_at, version FROM token WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    @Nested
    class Open {

        @Test
        void opensTodayInTheClinicTimeZoneAndRecordsTheEvent() {
            QueueDayView day = service.open(receptionist, queueId);

            assertThat(day.status()).isEqualTo(QueueDayStatus.OPEN);
            assertThat(day.serviceDate()).isEqualTo(CLINIC_DATE);
            assertThat(day.openedAt()).isEqualTo(NOW);
            assertThat(day.lastNumber()).isZero();
            assertThat(day.lastEventSeq()).isEqualTo(1);
            assertThat(service.today(receptionist, queueId)).contains(day);

            var events = events(day.id());
            assertThat(events).hasSize(1);
            assertThat(events.getFirst())
                    .containsEntry("seq", 1L)
                    .containsEntry("type", "DAY_OPENED")
                    .containsEntry("actor_type", "STAFF")
                    .containsEntry("actor_id", receptionist.userId());
            assertThat(events.getFirst().get("token_id")).isNull();

            var outbox = outbox(day.id());
            assertThat(outbox).hasSize(1);
            assertThat(outbox.getFirst())
                    .containsEntry("event_type", "QueueDayOpened")
                    .containsEntry("partition_key", day.id().toString());
            assertThat(outbox.getFirst().get("published_at")).isNull();
            assertThat((String) outbox.getFirst().get("payload"))
                    .contains(day.id().toString())
                    .contains(queueId.toString())
                    .contains("\"seq\": 1");
        }

        @Test
        void openingAgainReturnsTheSameDayWithoutNewEvents() {
            QueueDayView first = service.open(admin, queueId);
            QueueDayView second = service.open(receptionist, queueId);

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(dayRows()).isEqualTo(1);
            assertThat(events(first.id())).hasSize(1);
            assertThat(outbox(first.id())).hasSize(1);
        }

        @Test
        void openingAPausedDayLeavesItPaused() {
            service.open(admin, queueId);
            service.pause(admin, queueId);

            assertThat(service.open(admin, queueId).status()).isEqualTo(QueueDayStatus.PAUSED);
        }

        @Test
        void concurrentOpensCreateExactlyOneDay() throws Exception {
            int threads = 20;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<QueueDayView>> results = new ArrayList<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threads; i++) {
                    results.add(executor.submit(() -> {
                        start.await();
                        return service.open(admin, queueId);
                    }));
                }
                start.countDown();
            }

            UUID dayId = results.getFirst().get().id();
            for (Future<QueueDayView> result : results) {
                assertThat(result.get().id()).isEqualTo(dayId);
            }
            assertThat(dayRows()).isEqualTo(1);
            assertThat(events(dayId)).hasSize(1);
            assertThat(outbox(dayId)).hasSize(1);
        }

        @Test
        void theNextDateGetsANewDay() {
            QueueDayView today = service.open(admin, queueId);
            service.close(admin, queueId);

            CLOCK.advance(Duration.ofDays(1));
            QueueDayView tomorrow = service.open(admin, queueId);

            assertThat(tomorrow.id()).isNotEqualTo(today.id());
            assertThat(tomorrow.serviceDate()).isEqualTo(CLINIC_DATE.plusDays(1));
            assertThat(tomorrow.status()).isEqualTo(QueueDayStatus.OPEN);
            assertThat(tomorrow.lastEventSeq()).isEqualTo(1);
        }

        @Test
        void todayIsEmptyBeforeOpening() {
            assertThat(service.today(admin, queueId)).isEmpty();
        }
    }

    @Nested
    class PauseAndResume {

        @Test
        void pauseAndResumeChangeStatusAndEachRecordAnEvent() {
            UUID dayId = service.open(admin, queueId).id();

            assertThat(service.pause(admin, queueId).status()).isEqualTo(QueueDayStatus.PAUSED);
            assertThat(service.resume(receptionist, queueId).status()).isEqualTo(QueueDayStatus.OPEN);

            assertThat(events(dayId))
                    .extracting(row -> row.get("seq") + ":" + row.get("type"))
                    .containsExactly("1:DAY_OPENED", "2:DAY_PAUSED", "3:DAY_RESUMED");
            assertThat(outbox(dayId))
                    .extracting(row -> row.get("event_type"))
                    .containsExactlyInAnyOrder("QueueDayOpened", "QueueDayPaused", "QueueDayResumed");
        }

        @Test
        void repeatingAPauseOrResumeChangesNothing() {
            UUID dayId = service.open(admin, queueId).id();

            service.resume(admin, queueId); // already open
            service.pause(admin, queueId);
            service.pause(admin, queueId); // already paused

            assertThat(events(dayId)).extracting(row -> row.get("type")).containsExactly("DAY_OPENED", "DAY_PAUSED");
        }

        @Test
        void nothingCanBeDoneBeforeTheDayIsOpened() {
            assertThatThrownBy(() -> service.pause(admin, queueId)).isInstanceOf(QueueNotOpenedException.class);
            assertThatThrownBy(() -> service.resume(admin, queueId)).isInstanceOf(QueueNotOpenedException.class);
            assertThatThrownBy(() -> service.close(admin, queueId)).isInstanceOf(QueueNotOpenedException.class);
            assertThat(dayRows()).isZero();
        }
    }

    @Nested
    class Close {

        @Test
        void closeExpiresOpenTokensAndAutoCompletesTheConsultationInProgress() {
            QueueDayView day = service.open(admin, queueId);
            UUID clinicId = day.clinicId();
            UUID waiting = insertToken(day.id(), clinicId, 1, TokenStatus.WAITING);
            UUID called = insertToken(day.id(), clinicId, 2, TokenStatus.CALLED);
            UUID noShow = insertToken(day.id(), clinicId, 3, TokenStatus.NO_SHOW);
            UUID inConsultation = insertToken(day.id(), clinicId, 4, TokenStatus.IN_CONSULTATION);
            UUID completed = insertToken(day.id(), clinicId, 5, TokenStatus.COMPLETED);
            UUID cancelled = insertToken(day.id(), clinicId, 6, TokenStatus.CANCELLED);
            CLOCK.advance(Duration.ofHours(8));
            Instant closeTime = CLOCK.instant();

            QueueDayView closed = service.close(admin, queueId);

            assertThat(closed.status()).isEqualTo(QueueDayStatus.CLOSED);
            assertThat(closed.closedAt()).isEqualTo(closeTime);
            for (UUID id : List.of(waiting, called, noShow)) {
                assertThat(token(id)).containsEntry("status", "EXPIRED").containsEntry("flags", "{}");
                assertThat(token(id).get("version")).isEqualTo(1L);
            }
            Map<String, Object> autoClosed = token(inConsultation);
            assertThat(autoClosed).containsEntry("status", "COMPLETED").containsEntry("flags", "{AUTO_CLOSED}");
            assertThat(((Timestamp) autoClosed.get("completed_at")).toInstant()).isEqualTo(closeTime);
            // Tokens that were already finished are left alone.
            assertThat(token(completed)).containsEntry("status", "COMPLETED").containsEntry("version", 0L);
            assertThat(token(cancelled)).containsEntry("status", "CANCELLED").containsEntry("version", 0L);

            // One event per changed token, then the day event; the sequence has no gaps.
            var events = events(day.id());
            assertThat(events)
                    .extracting(row -> row.get("seq") + ":" + row.get("type"))
                    .containsExactly(
                            "1:DAY_OPENED", "2:EXPIRED", "3:EXPIRED", "4:EXPIRED", "5:COMPLETED", "6:DAY_CLOSED");
            assertThat(events.subList(1, 5))
                    .extracting(row -> row.get("token_id"))
                    .containsExactly(waiting, called, noShow, inConsultation);
            assertThat(closed.lastEventSeq()).isEqualTo(6);

            var closedEvent = outbox(day.id()).stream()
                    .filter(row -> row.get("event_type").equals("QueueDayClosed"))
                    .toList();
            assertThat(closedEvent).hasSize(1);
            assertThat((String) closedEvent.getFirst().get("payload"))
                    .contains("\"expired\": 3")
                    .contains("\"autoCompleted\": 1")
                    .contains("\"seq\": 6");
        }

        @Test
        void closingAnEmptyDayWorks() {
            UUID dayId = service.open(admin, queueId).id();

            assertThat(service.close(admin, queueId).status()).isEqualTo(QueueDayStatus.CLOSED);
            assertThat(events(dayId)).extracting(row -> row.get("type")).containsExactly("DAY_OPENED", "DAY_CLOSED");
        }

        @Test
        void aClosedDayStaysClosed() {
            UUID dayId = service.open(admin, queueId).id();
            QueueDayView closed = service.close(admin, queueId);

            assertThat(service.close(admin, queueId)).isEqualTo(closed); // idempotent, no new events
            assertThatThrownBy(() -> service.open(admin, queueId)).isInstanceOf(QueueClosedException.class);
            assertThatThrownBy(() -> service.pause(admin, queueId)).isInstanceOf(QueueClosedException.class);
            assertThatThrownBy(() -> service.resume(admin, queueId)).isInstanceOf(QueueClosedException.class);
            assertThat(events(dayId)).hasSize(2);
            assertThat(outbox(dayId)).hasSize(2);
        }

        @Test
        void aPausedDayCanBeClosed() {
            service.open(admin, queueId);
            service.pause(admin, queueId);

            assertThat(service.close(admin, queueId).status()).isEqualTo(QueueDayStatus.CLOSED);
        }
    }

    @Nested
    class Access {

        @Test
        void assignedDoctorAndClinicStaffMayRunTheQueue() {
            StaffPrincipal assignedDoctor = principal(StaffRole.DOCTOR, admin.clinicId(), doctor.id());

            assertThat(service.open(assignedDoctor, queueId).status()).isEqualTo(QueueDayStatus.OPEN);
            assertThat(service.pause(receptionist, queueId).status()).isEqualTo(QueueDayStatus.PAUSED);
            assertThat(service.resume(admin, queueId).status()).isEqualTo(QueueDayStatus.OPEN);
        }

        @Test
        void doctorNotAssignedToTheQueueGetsNotFound() {
            DoctorView other = doctorAdmin.create(admin, new DoctorDetails("Dr. Gill", null));
            StaffPrincipal otherDoctor = principal(StaffRole.DOCTOR, admin.clinicId(), other.id());

            assertThatThrownBy(() -> service.open(otherDoctor, queueId)).isInstanceOf(NotFoundException.class);
            assertThat(dayRows()).isZero();
        }

        @Test
        void anotherClinicGetsNotFoundAndChangesNothing() {
            service.open(admin, queueId);
            StaffPrincipal otherAdmin =
                    principal(StaffRole.CLINIC_ADMIN, newClinic().id(), null);

            assertThatThrownBy(() -> service.today(otherAdmin, queueId)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.pause(otherAdmin, queueId)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.close(otherAdmin, queueId)).isInstanceOf(NotFoundException.class);
            assertThat(service.today(admin, queueId).orElseThrow().status()).isEqualTo(QueueDayStatus.OPEN);
        }

        @Test
        void unknownQueueIsNotFoundAndPlatformAdminIsForbidden() {
            assertThatThrownBy(() -> service.open(admin, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.open(PLATFORM, queueId)).isInstanceOf(ForbiddenException.class);
        }
    }
}
