package com.baari.queue;

import com.baari.clinic.QueueInfo;
import com.baari.clinic.StaffPrincipal;
import com.baari.common.Ids;
import com.baari.queue.QueueDayEvents.QueueDayClosed;
import com.baari.queue.QueueDayEvents.QueueDayOpened;
import com.baari.queue.QueueDayEvents.QueueDayPaused;
import com.baari.queue.QueueDayEvents.QueueDayResumed;
import com.baari.queue.internal.EventLog;
import com.baari.queue.internal.EventType;
import com.baari.queue.internal.QueueDayEntity;
import com.baari.queue.internal.QueueDayRepository;
import com.baari.queue.internal.QueueDaySql;
import com.baari.queue.internal.TokenEntity;
import com.baari.queue.internal.TokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens, pauses, resumes and closes today's queue day (PLAN §3, §12 Phase 2). "Today" is the
 * service date in the clinic's time zone, taken from the injected clock.
 *
 * <p>Repeating an action whose result is already in place (open an open day, pause a paused one,
 * close a closed one) does nothing and returns the day, so a double-click or a retried request is
 * harmless. Nothing can change a closed day.
 */
@Service
public class QueueDayService {

    /** Statuses day close still has to deal with. */
    private static final Set<TokenStatus> OPEN_TOKEN_STATUSES =
            EnumSet.of(TokenStatus.WAITING, TokenStatus.CALLED, TokenStatus.NO_SHOW, TokenStatus.IN_CONSULTATION);

    private final QueueAccess access;
    private final QueueDayRepository days;
    private final QueueDaySql daySql;
    private final TokenRepository tokens;
    private final EventLog events;
    private final Clock clock;

    QueueDayService(
            QueueAccess access,
            QueueDayRepository days,
            QueueDaySql daySql,
            TokenRepository tokens,
            EventLog events,
            Clock clock) {
        this.access = access;
        this.days = days;
        this.daySql = daySql;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
    }

    /** Today's day for the queue, if it has been opened. */
    @Transactional(readOnly = true)
    public Optional<QueueDayView> today(StaffPrincipal caller, UUID queueId) {
        QueueInfo info = access.requireStaff(caller, queueId);
        return days.findByQueueIdAndServiceDate(queueId, today(info)).map(QueueDayEntity::toView);
    }

    /**
     * Opens today's day. Idempotent for the same date, even when two staff open it at once (see
     * {@link QueueDaySql#insertIfAbsent}). Throws {@link QueueClosedException} if today's day was
     * already closed: a closed day stays closed.
     */
    @Transactional
    public QueueDayView open(StaffPrincipal caller, UUID queueId) {
        QueueInfo info = access.requireStaff(caller, queueId);
        Instant now = clock.instant();
        LocalDate date = today(info);
        boolean created =
                daySql.insertIfAbsent(Ids.newId(), queueId, info.queue().clinicId(), date, now);
        QueueDayEntity day = days.lockByQueueIdAndServiceDate(queueId, date).orElseThrow();
        if (created) {
            long seq = events.append(day, null, EventType.DAY_OPENED, ActorType.STAFF, caller.userId(), now, Map.of());
            events.publish(new QueueDayOpened(Ids.newId(), now, day.getClinicId(), day.getId(), seq, queueId));
        } else if (day.getStatus() == QueueDayStatus.CLOSED) {
            throw new QueueClosedException();
        }
        return day.toView();
    }

    /** Stops new joins. Patients already waiting are still served. */
    @Transactional
    public QueueDayView pause(StaffPrincipal caller, UUID queueId) {
        QueueDayEntity day = lockToday(caller, queueId);
        switch (day.getStatus()) {
            case CLOSED -> throw new QueueClosedException();
            case PAUSED -> {
                // already paused: nothing to do
            }
            case OPEN -> {
                Instant now = clock.instant();
                day.pause();
                long seq =
                        events.append(day, null, EventType.DAY_PAUSED, ActorType.STAFF, caller.userId(), now, Map.of());
                events.publish(new QueueDayPaused(Ids.newId(), now, day.getClinicId(), day.getId(), seq));
            }
        }
        return day.toView();
    }

    @Transactional
    public QueueDayView resume(StaffPrincipal caller, UUID queueId) {
        QueueDayEntity day = lockToday(caller, queueId);
        switch (day.getStatus()) {
            case CLOSED -> throw new QueueClosedException();
            case OPEN -> {
                // already open: nothing to do
            }
            case PAUSED -> {
                Instant now = clock.instant();
                day.resume();
                long seq = events.append(
                        day, null, EventType.DAY_RESUMED, ActorType.STAFF, caller.userId(), now, Map.of());
                events.publish(new QueueDayResumed(Ids.newId(), now, day.getClinicId(), day.getId(), seq));
            }
        }
        return day.toView();
    }

    /**
     * Closes today's day. Every token still open goes through the state machine's day close:
     * waiting, called and no-show tokens expire; a consultation in progress is completed at close
     * time and flagged AUTO_CLOSED (PLAN §4). Each token change gets its own {@code token_event};
     * one {@link QueueDayClosed} event with the counts goes to the outbox.
     *
     * <p>The day's row lock is taken before the tokens are read. A join takes the same lock to get
     * its number, so a join racing with close either commits first (its token is then expired here)
     * or runs after and finds the day closed (PLAN §6.4 test 6).
     */
    @Transactional
    public QueueDayView close(StaffPrincipal caller, UUID queueId) {
        QueueDayEntity day = lockToday(caller, queueId);
        if (day.getStatus() == QueueDayStatus.CLOSED) {
            return day.toView();
        }
        Instant now = clock.instant();
        int expired = 0;
        int autoCompleted = 0;
        List<TokenEntity> open =
                tokens.findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(day.getId(), OPEN_TOKEN_STATUSES);
        for (TokenEntity token : open) {
            TokenStatus before = token.closeForDay(now);
            EventType type = token.getStatus() == TokenStatus.EXPIRED ? EventType.EXPIRED : EventType.COMPLETED;
            if (type == EventType.EXPIRED) {
                expired++;
            } else {
                autoCompleted++;
            }
            events.append(
                    day,
                    token.getId(),
                    type,
                    ActorType.STAFF,
                    caller.userId(),
                    now,
                    Map.of("from", before.name(), "to", token.getStatus().name(), "flags", token.getFlags()));
        }
        day.close(now);
        long seq = events.append(
                day,
                null,
                EventType.DAY_CLOSED,
                ActorType.STAFF,
                caller.userId(),
                now,
                Map.of("expired", expired, "autoCompleted", autoCompleted));
        events.publish(
                new QueueDayClosed(Ids.newId(), now, day.getClinicId(), day.getId(), seq, expired, autoCompleted));
        return day.toView();
    }

    private QueueDayEntity lockToday(StaffPrincipal caller, UUID queueId) {
        QueueInfo info = access.requireStaff(caller, queueId);
        return days.lockByQueueIdAndServiceDate(queueId, today(info)).orElseThrow(QueueNotOpenedException::new);
    }

    private LocalDate today(QueueInfo info) {
        return clock.instant().atZone(info.timezone()).toLocalDate();
    }
}
