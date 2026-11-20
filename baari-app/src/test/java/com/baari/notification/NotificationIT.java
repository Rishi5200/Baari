package com.baari.notification;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.baari.events.EventHeaders;
import com.baari.events.EventTopics;
import com.baari.queue.IssuedToken;
import com.baari.queue.Priority;
import com.baari.queue.QueueDayService;
import com.baari.queue.TokenActionService;
import com.baari.queue.TokenService;
import com.baari.queue.VisitType;
import com.baari.queue.WalkInRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Notification planning and dispatch against real Postgres and Kafka (PLAN §11.3, §11.4, §12
 * Phase 5). The scheduled dispatcher is turned off so tests decide when a batch is sent; planning
 * still runs from the real Kafka events. A recording channel stands in for a messaging service.
 */
@Import(NotificationIT.TestChannelConfig.class)
@TestPropertySource(
        properties = {"baari.notifications.dispatch-delay-ms=3600000", "baari.notifications.log-enabled=false"})
class NotificationIT extends AbstractIntegrationTest {

    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    /** Records what it sends, and can be told to fail for a given token. */
    static class RecordingChannel implements NotificationChannel {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

        @Override
        public String name() {
            return "TEST";
        }

        @Override
        public boolean canReach(UUID tokenId) {
            return true;
        }

        @Override
        public void send(UUID tokenId, String text) {
            AtomicInteger left = failuresLeft.get(tokenId);
            if (left != null && left.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("channel is down");
            }
            sent.add(tokenId + "|" + text);
        }

        List<String> sentTo(UUID tokenId) {
            return sent.stream()
                    .filter(s -> s.startsWith(tokenId + "|"))
                    .map(s -> s.substring(s.indexOf('|') + 1))
                    .toList();
        }
    }

    @TestConfiguration
    static class TestChannelConfig {
        @Bean
        RecordingChannel recordingChannel() {
            return new RecordingChannel();
        }
    }

    @Autowired
    RecordingChannel channel;

    @Autowired
    NotificationDispatcher dispatcher;

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
    KafkaTemplate<String, String> kafka;

    StaffPrincipal receptionist;
    StaffPrincipal doctor;
    UUID queue;

    @BeforeEach
    void setUp() {
        ClinicView clinic = clinicAdmin.createClinic(
                PLATFORM,
                new NewClinic(
                        "clinic-" + UUID.randomUUID().toString().substring(0, 8),
                        "Clinic",
                        "Asia/Kolkata",
                        "admin-" + UUID.randomUUID().toString().substring(0, 8),
                        "s3cret-pass"));
        StaffPrincipal admin =
                new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, clinic.id(), null);
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

    private IssuedToken walkIn() {
        return tokens.issueWalkIn(
                receptionist, queue, new WalkInRequest("Patient", null, VisitType.NEW, Priority.NORMAL, null));
    }

    private int rows(UUID tokenId, String kind) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE token_id = ? AND kind = ? AND channel = 'TEST'")
                .param(tokenId)
                .param(kind)
                .query(Integer.class)
                .single();
    }

    private String status(UUID tokenId, String kind) {
        return jdbc.sql("SELECT status FROM notification WHERE token_id = ? AND kind = ? AND channel = 'TEST'")
                .param(tokenId)
                .param(kind)
                .query(String.class)
                .single();
    }

    private void awaitPlanned(UUID tokenId, String kind) {
        await().atMost(Duration.ofSeconds(30)).until(() -> rows(tokenId, kind) == 1);
    }

    @Test
    void patientsWithinThreePlacesAreToldToComeBackAndTheCalledPatientIsToldItIsTheirTurn() {
        IssuedToken first = walkIn();
        IssuedToken second = walkIn();
        IssuedToken third = walkIn();
        IssuedToken fourth = walkIn();

        awaitPlanned(first.tokenId(), "NEAR_TURN");
        awaitPlanned(second.tokenId(), "NEAR_TURN");
        awaitPlanned(third.tokenId(), "NEAR_TURN");
        assertThat(rows(fourth.tokenId(), "NEAR_TURN"))
                .as("fourth is 4th in line")
                .isZero();

        dispatcher.dispatchDue();
        assertThat(channel.sentTo(first.tokenId())).singleElement().satisfies(text -> {
            assertThat(text).contains(first.displayCode());
            assertThat(text).contains("0 patients ahead");
            assertThat(text).contains("Please return to the clinic");
        });
        assertThat(channel.sentTo(third.tokenId()))
                .singleElement()
                .satisfies(text -> assertThat(text).contains("2 patients ahead"));

        actions.callNext(doctor, queue, null);
        awaitPlanned(first.tokenId(), "CALLED");
        awaitPlanned(fourth.tokenId(), "NEAR_TURN"); // the line moved up
        dispatcher.dispatchDue();

        assertThat(channel.sentTo(first.tokenId())).anyMatch(t -> t.contains("it is your turn"));
        assertThat(status(first.tokenId(), "CALLED")).isEqualTo("SENT");
        assertThat(channel.sentTo(fourth.tokenId()))
                .singleElement()
                .satisfies(text -> assertThat(text).contains(fourth.displayCode()));
    }

    @Test
    void aComeBackMessageIsSkippedWhenThePatientIsNoLongerWaiting() {
        IssuedToken first = walkIn();
        IssuedToken second = walkIn();
        awaitPlanned(first.tokenId(), "NEAR_TURN");
        awaitPlanned(second.tokenId(), "NEAR_TURN");

        actions.cancel(receptionist, first.tokenId());
        dispatcher.dispatchDue();

        assertThat(status(first.tokenId(), "NEAR_TURN")).isEqualTo("SKIPPED");
        assertThat(channel.sentTo(first.tokenId())).isEmpty();
        assertThat(status(second.tokenId(), "NEAR_TURN")).isEqualTo("SENT");
    }

    @Test
    void aFailingChannelIsRetriedWithBackoffAndMarkedFailedAfterFiveAttempts() {
        IssuedToken only = walkIn();
        channel.failuresLeft.put(only.tokenId(), new AtomicInteger(5));
        awaitPlanned(only.tokenId(), "NEAR_TURN");

        dispatcher.dispatchDue();

        assertThat(status(only.tokenId(), "NEAR_TURN")).isEqualTo("PENDING");
        assertThat(jdbc.sql("""
                                SELECT attempts = 1 AND next_attempt_at > now() + interval '20 seconds' AND last_error IS NOT NULL
                                  FROM notification WHERE token_id = ? AND kind = 'NEAR_TURN' AND channel = 'TEST'
                                """).param(only.tokenId()).query(Boolean.class).single())
                .as("one attempt, retry about 30 s later, error kept")
                .isTrue();

        for (int attempt = 2; attempt <= 5; attempt++) {
            makeDueNow(only.tokenId());
            dispatcher.dispatchDue();
        }

        assertThat(status(only.tokenId(), "NEAR_TURN")).isEqualTo("FAILED");
        assertThat(jdbc.sql(
                                "SELECT attempts FROM notification WHERE token_id = ? AND kind = 'NEAR_TURN' AND channel = 'TEST'")
                        .param(only.tokenId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(5);
        assertThat(channel.sentTo(only.tokenId())).isEmpty();
    }

    @Test
    void aFailureThatClearsUpIsDeliveredOnTheRetry() {
        IssuedToken only = walkIn();
        channel.failuresLeft.put(only.tokenId(), new AtomicInteger(1));
        awaitPlanned(only.tokenId(), "NEAR_TURN");

        dispatcher.dispatchDue();
        assertThat(status(only.tokenId(), "NEAR_TURN")).isEqualTo("PENDING");
        makeDueNow(only.tokenId());
        dispatcher.dispatchDue();

        assertThat(status(only.tokenId(), "NEAR_TURN")).isEqualTo("SENT");
        assertThat(channel.sentTo(only.tokenId())).hasSize(1);
    }

    @Test
    void theSameEventDeliveredTwiceCreatesAndSendsOneNotification() {
        IssuedToken first = walkIn();
        actions.callNext(doctor, queue, null);
        awaitPlanned(first.tokenId(), "CALLED");

        UUID eventId = jdbc.sql("""
                                SELECT id FROM outbox_event
                                 WHERE event_type = 'TokenCalled' AND payload->>'tokenId' = ?
                                """)
                .param(first.tokenId().toString())
                .query(UUID.class)
                .single();
        String payload = jdbc.sql("SELECT CAST(payload AS text) FROM outbox_event WHERE id = ?")
                .param(eventId)
                .query(String.class)
                .single();
        ProducerRecord<String, String> again = new ProducerRecord<>(EventTopics.QUEUE_EVENTS, "again", payload);
        again.headers().add(EventHeaders.EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8));
        again.headers().add(EventHeaders.EVENT_TYPE, "TokenCalled".getBytes(StandardCharsets.UTF_8));
        kafka.send(again).join();
        // A later event proves the planner got past the duplicate.
        IssuedToken later = walkIn();
        awaitPlanned(later.tokenId(), "NEAR_TURN");

        dispatcher.dispatchDue();

        assertThat(rows(first.tokenId(), "CALLED")).isEqualTo(1);
        assertThat(channel.sentTo(first.tokenId()).stream().filter(t -> t.contains("it is your turn")))
                .hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_event WHERE consumer = ? AND event_id = ?")
                        .param(NotificationPlanner.CONSUMER)
                        .param(eventId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void patientsStillOpenWhenTheDayClosesAreToldTheQueueHasClosed() {
        IssuedToken waiting = walkIn();
        awaitPlanned(waiting.tokenId(), "NEAR_TURN");
        dispatcher.dispatchDue();
        StaffPrincipal admin =
                new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, receptionist.clinicId(), null);

        days.close(admin, queue);
        awaitPlanned(waiting.tokenId(), "QUEUE_CLOSED");
        dispatcher.dispatchDue();

        assertThat(channel.sentTo(waiting.tokenId())).anyMatch(t -> t.contains("queue has closed"));
    }

    private void makeDueNow(UUID tokenId) {
        jdbc.sql("UPDATE notification SET next_attempt_at = now() - interval '1 second' WHERE token_id = ?")
                .param(tokenId)
                .update();
    }
}
