package com.baari.notification;

import com.baari.eta.EtaDisplay;
import com.baari.eta.EtaService;
import com.baari.eta.TokenEstimate;
import com.baari.queue.QueueQueryService;
import com.baari.queue.TokenAlertView;
import com.baari.queue.TokenStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the pending notifications (PLAN §11.4). Every two seconds it claims due rows with
 * {@code FOR UPDATE SKIP LOCKED} (so several instances never send the same one), checks that the
 * message is still true, sends it through its channel and records the result.
 *
 * <p>A failed send is retried after 30 s, 2 min, 10 min and 30 min, and the row is marked FAILED
 * after {@code maxAttempts} tries. A "come back" message for a patient who is no longer waiting, or
 * a "your turn" message for one who is no longer called, is SKIPPED: it would only confuse them.
 * The external send happens while the row is locked; with one send per row and a short timeout in
 * each channel that is acceptable for now (ADR 0011).
 */
@Component
class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int BATCH = 50;
    private static final List<Duration> BACKOFF =
            List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30));

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final List<NotificationChannel> channels;
    private final QueueQueryService queues;
    private final EtaService eta;
    private final MessageSource messages;
    private final NotificationProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    NotificationDispatcher(
            JdbcClient jdbc,
            TransactionTemplate tx,
            List<NotificationChannel> channels,
            QueueQueryService queues,
            EtaService eta,
            MessageSource messages,
            NotificationProperties props,
            MeterRegistry meters,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.channels = channels;
        this.queues = queues;
        this.eta = eta;
        this.messages = messages;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    private record Due(UUID id, UUID tokenId, String kind, String channel, int attempts) {}

    @Scheduled(fixedDelayString = "${baari.notifications.dispatch-delay-ms:2000}")
    void dispatch() {
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            log.warn("Notification dispatch run failed: {}", e.toString());
        }
    }

    /** Handles one batch of due notifications and returns how many it looked at. */
    int dispatchDue() {
        Integer handled = tx.execute(status -> {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            List<Due> due = jdbc.sql("""
                            SELECT id, token_id, kind, channel, attempts
                              FROM notification
                             WHERE status = 'PENDING' AND next_attempt_at <= :now AND channel IN (:channels)
                             ORDER BY next_attempt_at, id
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED
                            """)
                    .param("now", now)
                    .param(
                            "channels",
                            channels.stream().map(NotificationChannel::name).toList())
                    .param("limit", BATCH)
                    .query((rs, n) -> new Due(
                            rs.getObject("id", UUID.class),
                            rs.getObject("token_id", UUID.class),
                            rs.getString("kind"),
                            rs.getString("channel"),
                            rs.getInt("attempts")))
                    .list();
            due.forEach(this::handle);
            return due.size();
        });
        return handled == null ? 0 : handled;
    }

    private void handle(Due n) {
        NotificationKind kind = NotificationKind.valueOf(n.kind());
        Optional<TokenAlertView> view = queues.alertView(n.tokenId());
        Optional<NotificationChannel> channel =
                channels.stream().filter(c -> c.name().equals(n.channel())).findFirst();
        if (view.isEmpty() || channel.isEmpty() || !stillTrue(kind, view.get().status())) {
            finish(n.id(), "SKIPPED", n.attempts(), null);
            count(kind, n.channel(), "skipped");
            return;
        }
        try {
            channel.get().send(n.tokenId(), text(kind, view.get(), Locale.ENGLISH));
            finish(n.id(), "SENT", n.attempts() + 1, null);
            count(kind, n.channel(), "sent");
        } catch (RuntimeException e) {
            int attempts = n.attempts() + 1;
            log.warn("Notification {} via {} failed (attempt {}): {}", n.id(), n.channel(), attempts, e.toString());
            if (attempts >= props.maxAttempts()) {
                finish(n.id(), "FAILED", attempts, e.toString());
                count(kind, n.channel(), "failed");
            } else {
                retryLater(n.id(), attempts, e.toString());
                count(kind, n.channel(), "retry");
            }
        }
    }

    private static boolean stillTrue(NotificationKind kind, TokenStatus status) {
        return switch (kind) {
            case NEAR_TURN -> status == TokenStatus.WAITING;
            case CALLED -> status == TokenStatus.CALLED;
            case QUEUE_CLOSED -> true;
        };
    }

    private String text(NotificationKind kind, TokenAlertView view, Locale locale) {
        return switch (kind) {
            case CALLED -> messages.getMessage("notify.called", new Object[] {view.displayCode()}, locale);
            case QUEUE_CLOSED -> messages.getMessage("notify.closed", new Object[] {view.displayCode()}, locale);
            case NEAR_TURN -> {
                TokenEstimate estimate = eta.estimates(view.queueId()).get(view.tokenId());
                int ahead = estimate == null ? 0 : Math.max(0, estimate.position() - 1);
                String when = eta.displayOf(view.queueId(), view.tokenId())
                        .map(d -> describe(d, locale))
                        .orElseGet(() -> messages.getMessage("notify.soon", null, locale));
                yield messages.getMessage("notify.near_turn", new Object[] {view.displayCode(), ahead, when}, locale);
            }
        };
    }

    private String describe(EtaDisplay display, Locale locale) {
        return switch (display.kind()) {
            case FEW_MINUTES -> messages.getMessage("notify.eta.few", null, locale);
            case RANGE ->
                messages.getMessage(
                        "notify.eta.range", new Object[] {display.fromMinutes(), display.toMinutes()}, locale);
            case OVER_TWO_HOURS -> messages.getMessage("notify.eta.over", null, locale);
        };
    }

    private void finish(UUID id, String status, int attempts, String error) {
        jdbc.sql("""
                        UPDATE notification
                           SET status = :status, attempts = :attempts, last_error = :error,
                               sent_at = CASE WHEN :status = 'SENT' THEN :now ELSE sent_at END
                         WHERE id = :id
                        """)
                .param("status", status)
                .param("attempts", attempts)
                .param("error", error)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    private void retryLater(UUID id, int attempts, String error) {
        Instant next = clock.instant().plus(BACKOFF.get(Math.min(attempts, BACKOFF.size()) - 1));
        jdbc.sql("""
                        UPDATE notification
                           SET attempts = :attempts, last_error = :error, next_attempt_at = :next
                         WHERE id = :id
                        """)
                .param("attempts", attempts)
                .param("error", error)
                .param("next", OffsetDateTime.ofInstant(next, ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    private void count(NotificationKind kind, String channel, String outcome) {
        meters.counter("baari.notifications", "kind", kind.name(), "channel", channel, "outcome", outcome)
                .increment();
    }
}
