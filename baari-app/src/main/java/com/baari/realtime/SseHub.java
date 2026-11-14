package com.baari.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the open SSE connections of this instance and pushes fresh snapshots to them (PLAN §10).
 *
 * <p>A change to a queue reaches {@link #queueChanged} (from Redis, so changes made on any
 * instance arrive here). Notifications for one queue are <b>coalesced</b>: the first one starts a
 * short timer, the ones that arrive meanwhile are merged into it, and when it fires every
 * connection of that queue gets one new snapshot. A burst of ten clicks therefore costs one
 * rebuild, not ten.
 *
 * <p>Snapshots are complete state, never deltas, so a client that missed messages (network gap,
 * reconnect) is correct again after the next one.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    static final Duration COALESCE = Duration.ofMillis(200);
    static final Duration HEARTBEAT = Duration.ofSeconds(15);
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final JsonMapper json;
    private final MeterRegistry meters;
    private final Duration coalesce;

    /** Connections by queue. Concurrent: requests add and remove while broadcasts iterate. */
    private final Map<UUID, Set<Subscriber>> byQueue = new ConcurrentHashMap<>();
    /** Highest sequence number waiting to be broadcast, per queue. Present = a flush is scheduled. */
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();

    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("sse-timer").factory());
    /** One virtual thread per send, so a slow client can't hold up the others. */
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    SseHub(JsonMapper json, MeterRegistry meters) {
        this(json, meters, COALESCE);
    }

    SseHub(JsonMapper json, MeterRegistry meters, Duration coalesce) {
        this.json = json;
        this.meters = meters;
        this.coalesce = coalesce;
    }

    @PostConstruct
    void startHeartbeat() {
        // A comment line every 15 s keeps proxies from closing idle connections and lets us
        // notice dead clients (the write fails).
        timer.scheduleAtFixedRate(this::heartbeat, HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
        sender.shutdownNow();
    }

    /**
     * Opens a stream. The snapshot is built once <em>before</em> the emitter is returned, so a bad
     * request (unknown token, wrong key, no access) fails as an ordinary HTTP error instead of an
     * empty stream, and a fresh client always starts from a complete state (so {@code
     * Last-Event-ID} needs no special handling).
     *
     * @param type "patient", "staff" or "display": used for the connection gauge
     * @param snapshot builds the complete current state for this connection
     */
    public SseEmitter subscribe(UUID queueId, String type, Supplier<?> snapshot) {
        String first = json.writeValueAsString(snapshot.get());
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscriber subscriber = new Subscriber(queueId, type, snapshot, new EmitterSink(emitter));
        register(subscriber);
        emitter.onCompletion(() -> unregister(subscriber));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> unregister(subscriber));
        subscriber.deliver(null, first);
        return emitter;
    }

    /** Test seam: register a connection that isn't an {@link SseEmitter}. */
    void register(Subscriber subscriber) {
        byQueue.computeIfAbsent(subscriber.queueId, id -> ConcurrentHashMap.newKeySet())
                .add(subscriber);
        counter(subscriber.type).incrementAndGet();
    }

    private void unregister(Subscriber subscriber) {
        Set<Subscriber> set = byQueue.get(subscriber.queueId);
        if (set != null && set.remove(subscriber)) {
            counter(subscriber.type).decrementAndGet();
            // Drop empty sets, or every queue ever watched would stay in the map.
            byQueue.computeIfPresent(subscriber.queueId, (id, s) -> s.isEmpty() ? null : s);
        }
    }

    /** A queue changed (on this or another instance). Coalesces, then pushes to local connections. */
    public void queueChanged(UUID queueId, long seq) {
        if (!byQueue.containsKey(queueId)) {
            return; // nobody here is watching this queue
        }
        boolean[] first = {false};
        pending.compute(queueId, (id, old) -> {
            if (old == null) {
                first[0] = true;
                return seq;
            }
            return Math.max(old, seq);
        });
        if (first[0]) {
            timer.schedule(() -> flush(queueId), coalesce.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void flush(UUID queueId) {
        // Remove before reading the subscribers: a change that arrives from now on schedules a
        // new flush, so no change is ever swallowed by a flush that already started.
        Long seq = pending.remove(queueId);
        Set<Subscriber> set = byQueue.get(queueId);
        if (seq == null || set == null) {
            return;
        }
        for (Subscriber subscriber : set) {
            sender.execute(() -> push(subscriber, seq));
        }
    }

    private void push(Subscriber subscriber, long seq) {
        try {
            subscriber.deliver(Long.toString(seq), json.writeValueAsString(subscriber.snapshot.get()));
        } catch (RuntimeException e) {
            // Typically "token no longer exists" or access revoked. End the stream; the browser
            // reconnects and gets a proper HTTP error if the problem is permanent.
            log.debug("closing stream of type {}: {}", subscriber.type, e.toString());
            subscriber.close();
            unregister(subscriber);
        }
    }

    void heartbeat() {
        byQueue.values()
                .forEach(set -> set.forEach(subscriber -> {
                    if (!subscriber.heartbeat()) {
                        unregister(subscriber);
                    }
                }));
    }

    private AtomicInteger counter(String type) {
        return connectionCounts.computeIfAbsent(type, t -> {
            AtomicInteger count = new AtomicInteger();
            Gauge.builder("baari.sse.connections", count, AtomicInteger::get)
                    .tag("type", t)
                    .register(meters);
            return count;
        });
    }

    int connections(String type) {
        AtomicInteger count = connectionCounts.get(type);
        return count == null ? 0 : count.get();
    }

    /** Where bytes go. An interface so the hub can be tested without an HTTP connection. */
    interface Sink {
        void send(String id, String data) throws IOException;

        void comment(String text) throws IOException;

        void complete();
    }

    /** One open connection. Sends are serialised: the emitter must not be written concurrently. */
    static final class Subscriber {
        final UUID queueId;
        final String type;
        final Supplier<?> snapshot;
        private final Sink sink;
        private boolean closed;

        Subscriber(UUID queueId, String type, Supplier<?> snapshot, Sink sink) {
            this.queueId = queueId;
            this.type = type;
            this.snapshot = snapshot;
            this.sink = sink;
        }

        synchronized void deliver(String id, String data) {
            if (closed) {
                return;
            }
            try {
                sink.send(id, data);
            } catch (IOException | IllegalStateException e) {
                close();
            }
        }

        synchronized boolean heartbeat() {
            if (closed) {
                return false;
            }
            try {
                sink.comment("keep-alive");
                return true;
            } catch (IOException | IllegalStateException e) {
                close();
                return false;
            }
        }

        synchronized void close() {
            if (!closed) {
                closed = true;
                sink.complete();
            }
        }
    }

    private static final class EmitterSink implements Sink {
        private final SseEmitter emitter;

        EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(String id, String data) throws IOException {
            SseEmitter.SseEventBuilder event =
                    SseEmitter.event().name("snapshot").data(data);
            if (id != null) {
                event.id(id);
            }
            emitter.send(event);
        }

        @Override
        public void comment(String text) throws IOException {
            emitter.send(SseEmitter.event().comment(text));
        }

        @Override
        public void complete() {
            emitter.complete();
        }
    }
}
