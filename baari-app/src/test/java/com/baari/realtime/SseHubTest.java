package com.baari.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hub's rules without any HTTP: coalescing, isolation between queues, cleanup of dead
 * connections, heartbeat and the connection gauge (PLAN section 10.2).
 */
@Tag("unit")
class SseHubTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private SimpleMeterRegistry meters;
    private SseHub hub;
    private UUID queue;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        hub = new SseHub(JsonMapper.builder().build(), meters, Duration.ofMillis(50));
        queue = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        hub.stop();
    }

    @Test
    void aBurstOfChangesIsCoalescedIntoOneSnapshot() {
        AtomicInteger builds = new AtomicInteger();
        RecordingSink sink = connect(queue, "patient", () -> "state-" + builds.incrementAndGet());

        for (int seq = 1; seq <= 10; seq++) {
            hub.queueChanged(queue, seq);
        }

        await().atMost(WAIT).until(() -> sink.sent.size() == 1);
        assertThat(sink.sent.getFirst()).isEqualTo("10:\"state-1\""); // newest sequence number, one build
        // and nothing else follows from the same burst
        pause(150);
        assertThat(sink.sent).hasSize(1);
        assertThat(builds).hasValue(1);
    }

    @Test
    void aChangeAfterTheFlushStartsANewRound() {
        RecordingSink sink = connect(queue, "patient", () -> "x");

        hub.queueChanged(queue, 1);
        await().atMost(WAIT).until(() -> sink.sent.size() == 1);
        hub.queueChanged(queue, 2);
        await().atMost(WAIT).until(() -> sink.sent.size() == 2);

        assertThat(sink.sent).containsExactly("1:\"x\"", "2:\"x\"");
    }

    @Test
    void everyConnectionOfTheQueueGetsTheSnapshotAndOtherQueuesStayQuiet() {
        UUID other = UUID.randomUUID();
        RecordingSink first = connect(queue, "patient", () -> "a");
        RecordingSink second = connect(queue, "staff", () -> "b");
        RecordingSink elsewhere = connect(other, "patient", () -> "c");

        hub.queueChanged(queue, 7);

        await().atMost(WAIT).until(() -> first.sent.size() == 1 && second.sent.size() == 1);
        pause(150);
        assertThat(first.sent).containsExactly("7:\"a\"");
        assertThat(second.sent).containsExactly("7:\"b\"");
        assertThat(elsewhere.sent).isEmpty();
    }

    @Test
    void aQueueNobodyWatchesCostsNothing() {
        AtomicInteger builds = new AtomicInteger();
        UUID other = UUID.randomUUID();
        connect(other, "patient", () -> builds.incrementAndGet());

        hub.queueChanged(queue, 1);

        pause(150);
        assertThat(builds).hasValue(0);
    }

    @Test
    void aSnapshotThatFailsEndsThatStreamOnly() {
        RecordingSink broken = connect(queue, "patient", () -> {
            throw new IllegalStateException("token is gone");
        });
        RecordingSink healthy = connect(queue, "patient", () -> "ok");
        assertThat(hub.connections("patient")).isEqualTo(2);

        hub.queueChanged(queue, 1);

        await().atMost(WAIT).until(() -> broken.completed && healthy.sent.size() == 1);
        assertThat(hub.connections("patient")).isEqualTo(1);
        assertThat(healthy.completed).isFalse();
    }

    @Test
    void aClientThatCannotBeWrittenToIsDroppedByTheHeartbeat() {
        RecordingSink alive = connect(queue, "display", () -> "x");
        RecordingSink dead = connect(queue, "display", () -> "x");
        dead.failWrites = true;

        hub.heartbeat();

        assertThat(alive.comments).containsExactly("keep-alive");
        assertThat(dead.completed).isTrue();
        assertThat(hub.connections("display")).isEqualTo(1);
    }

    @Test
    void connectionGaugeIsReportedPerType() {
        connect(queue, "patient", () -> "x");
        connect(queue, "patient", () -> "x");
        connect(queue, "staff", () -> "x");

        assertThat(meters.get("baari.sse.connections")
                        .tag("type", "patient")
                        .gauge()
                        .value())
                .isEqualTo(2.0);
        assertThat(meters.get("baari.sse.connections")
                        .tag("type", "staff")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
    }

    // --- helpers ---

    private RecordingSink connect(UUID queueId, String type, Supplier<?> snapshot) {
        RecordingSink sink = new RecordingSink();
        hub.register(new SseHub.Subscriber(queueId, type, snapshot, sink));
        return sink;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Remembers what it was asked to send, as "id:json". */
    private static final class RecordingSink implements SseHub.Sink {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<String> comments = new CopyOnWriteArrayList<>();
        volatile boolean completed;
        volatile boolean failWrites;

        @Override
        public void send(String id, String data) throws IOException {
            if (failWrites) {
                throw new IOException("broken pipe");
            }
            sent.add(id + ":" + data);
        }

        @Override
        public void comment(String text) throws IOException {
            if (failWrites) {
                throw new IOException("broken pipe");
            }
            comments.add(text);
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
