package com.baari.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.ClinicView;
import com.baari.clinic.CreatedQueue;
import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorDetails;
import com.baari.clinic.NewClinic;
import com.baari.clinic.QrMode;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.QueueDetails;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.queue.IssuedToken;
import com.baari.queue.Priority;
import com.baari.queue.QueueDayService;
import com.baari.queue.TokenActionService;
import com.baari.queue.TokenService;
import com.baari.queue.VisitType;
import com.baari.queue.WalkInRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live updates over real HTTP (PLAN section 10.3 and the Phase 3 "Done when"): a real server
 * port, a real Redis, and a plain {@link HttpClient} reading the event stream line by line.
 *
 * <ul>
 *   <li>A staff action reaches the patient's open stream well within a second.
 *   <li>A message published straight to Redis (what another app instance does) reaches a stream
 *       held by this instance.
 *   <li>The TV stream needs the display key and carries no patient names.
 * </ul>
 */
class StreamIT extends AbstractIntegrationTest {

    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @LocalServerPort
    int port;

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    @Autowired
    QueueDayService days;

    @Autowired
    TokenService tokens;

    @Autowired
    TokenActionService actions;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JsonMapper json;

    StaffPrincipal receptionist;
    StaffPrincipal doctor;
    UUID queueId;
    String displayKey;
    private final HttpClient http = HttpClient.newHttpClient();
    private SseClient client;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ClinicView clinic = clinicAdmin.createClinic(
                PLATFORM,
                new NewClinic("clinic-" + suffix, "Stream Clinic", "Asia/Kolkata", "adm-" + suffix, "s3cret-pass"));
        StaffPrincipal admin =
                new StaffPrincipal(UUID.randomUUID(), "adm-" + suffix, StaffRole.CLINIC_ADMIN, clinic.id(), null);
        receptionist =
                new StaffPrincipal(UUID.randomUUID(), "rec-" + suffix, StaffRole.RECEPTIONIST, clinic.id(), null);
        UUID doctorId = doctorAdmin
                .create(admin, new DoctorDetails("Dr. Stream", "Room 7"))
                .id();
        doctor = new StaffPrincipal(UUID.randomUUID(), "doc-" + suffix, StaffRole.DOCTOR, clinic.id(), doctorId);
        CreatedQueue created = queueAdmin.create(
                admin, "q", new QueueDetails("Stream Queue", QueueMode.SINGLE, QrMode.STATIC, "A", Set.of(doctorId)));
        queueId = created.queue().id();
        displayKey = created.displayKey();
        days.open(receptionist, queueId);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void patientSeesTheCallWithinOneSecond() throws Exception {
        IssuedToken first = walkIn("Asha");
        walkIn("Bimal");
        client = open(url("/api/public/tokens/" + first.publicCode() + "/stream"));

        JsonNode initial = client.nextSnapshot(Duration.ofSeconds(5));
        assertThat(initial.get("status").asString()).isEqualTo("WAITING");
        assertThat(initial.get("position").asInt()).isEqualTo(1);

        long start = System.nanoTime();
        actions.callNext(doctor, queueId, null);
        JsonNode called = client.nextSnapshot(Duration.ofSeconds(5));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(called.get("status").asString()).isEqualTo("CALLED");
        assertThat(called.get("room").asString()).isEqualTo("Room 7");
        assertThat(called.get("nowServing").get(0).asString()).isEqualTo("A-001");
        assertThat(millis).as("time from staff click to patient update").isLessThan(1000);
        assertThat(meters.get("baari.sse.connections")
                        .tag("type", "patient")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void aMessageFromAnotherInstanceReachesThisInstancesStream() throws Exception {
        IssuedToken token = walkIn("Chitra");
        client = open(url("/api/public/tokens/" + token.publicCode() + "/stream"));
        client.nextSnapshot(Duration.ofSeconds(5)); // the initial one

        // What a second app instance does after it commits a change: publish on the queue's channel.
        redis.convertAndSend(RedisFanOut.CHANNEL_PREFIX + queueId, "999");

        JsonNode pushed = client.nextSnapshot(Duration.ofSeconds(5));
        assertThat(pushed.get("displayCode").asString()).isEqualTo("A-001");
    }

    @Test
    void streamSurvivesAnUnrelatedQueueChanging() throws Exception {
        IssuedToken token = walkIn("Dev");
        client = open(url("/api/public/tokens/" + token.publicCode() + "/stream"));
        client.nextSnapshot(Duration.ofSeconds(5));

        redis.convertAndSend(RedisFanOut.CHANNEL_PREFIX + UUID.randomUUID(), "1");
        redis.convertAndSend("baari:queue:not-a-uuid", "1"); // malformed: must be ignored, not kill the listener

        assertThat(client.pollSnapshot(Duration.ofMillis(600))).isNull();
        redis.convertAndSend(RedisFanOut.CHANNEL_PREFIX + queueId, "5");
        assertThat(client.nextSnapshot(Duration.ofSeconds(5)).get("displayCode").asString())
                .isEqualTo("A-001");
    }

    @Test
    void displayStreamNeedsTheKeyAndShowsNoNames() throws Exception {
        walkIn("Secret Patient One");
        walkIn("Secret Patient Two");
        actions.callNext(doctor, queueId, null);

        assertThat(status("/api/display/queues/" + queueId + "/stream?key=wrong"))
                .isEqualTo(404);
        assertThat(status("/api/display/queues/" + queueId + "/stream")).isEqualTo(400);

        client = open(url("/api/display/queues/" + queueId + "/stream?key=" + displayKey));
        String raw = client.nextRaw(Duration.ofSeconds(5));
        JsonNode board = json.readTree(raw);

        assertThat(board.get("nowServing").get(0).get("code").asString()).isEqualTo("A-001");
        assertThat(board.get("nowServing").get(0).get("room").asString()).isEqualTo("Room 7");
        assertThat(board.get("next").get(0).asString()).isEqualTo("A-002");
        assertThat(board.get("waiting").asInt()).isEqualTo(1);
        assertThat(raw).doesNotContain("Secret").doesNotContain("Patient").doesNotContain("patientName");
    }

    @Test
    void unknownTokenAndAnonymousStaffStreamAreRefused() throws Exception {
        assertThat(status("/api/public/tokens/doesnotexist0000000000/stream")).isEqualTo(404);
        assertThat(status("/api/staff/queues/" + queueId + "/stream")).isEqualTo(401);
    }

    @Test
    void aClosedConnectionIsForgotten() throws Exception {
        IssuedToken token = walkIn("Esha");
        client = open(url("/api/public/tokens/" + token.publicCode() + "/stream"));
        client.nextSnapshot(Duration.ofSeconds(5));
        double before = meters.get("baari.sse.connections")
                .tag("type", "patient")
                .gauge()
                .value();

        // Closing the browser tab: the server notices on its next write and forgets the connection.
        client.close();
        redis.convertAndSend(RedisFanOut.CHANNEL_PREFIX + queueId, "10");
        pause(300);
        redis.convertAndSend(RedisFanOut.CHANNEL_PREFIX + queueId, "11");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(meters.get("baari.sse.connections")
                                .tag("type", "patient")
                                .gauge()
                                .value())
                        .isLessThan(before));
    }

    // --- helpers ---

    private IssuedToken walkIn(String name) {
        return tokens.issueWalkIn(
                receptionist, queueId, new WalkInRequest(name, null, VisitType.NEW, Priority.NORMAL, null));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private int status(String path) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url(path))).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private SseClient open(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        return new SseClient(response.body(), json);
    }

    /** Reads an event stream on a background thread and queues the data of each event. */
    private static final class SseClient implements AutoCloseable {
        private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        private final InputStream body;
        private final JsonMapper json;

        SseClient(InputStream body, JsonMapper json) {
            this.body = body;
            this.json = json;
            Thread.ofVirtual().start(this::read);
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        events.add(line.substring("data:".length()).strip());
                    }
                }
            } catch (IOException e) {
                // stream closed
            }
        }

        String nextRaw(Duration timeout) throws InterruptedException {
            String data = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(data).as("an event within " + timeout).isNotNull();
            return data;
        }

        JsonNode nextSnapshot(Duration timeout) throws InterruptedException {
            return json.readTree(nextRaw(timeout));
        }

        JsonNode pollSnapshot(Duration timeout) throws InterruptedException {
            String data = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return data == null ? null : json.readTree(data);
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException e) {
                // already closed
            }
        }
    }
}
