package com.baari.queue;

import com.baari.clinic.QueueDirectory;
import com.baari.clinic.QueueInfo;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.Ids;
import com.baari.common.InvalidRequestException;
import com.baari.common.NotFoundException;
import com.baari.queue.TokenEvents.ConsultCompleted;
import com.baari.queue.TokenEvents.ConsultStarted;
import com.baari.queue.TokenEvents.TokenCalled;
import com.baari.queue.TokenEvents.TokenCancelled;
import com.baari.queue.TokenEvents.TokenNoShow;
import com.baari.queue.TokenEvents.TokenPriorityChanged;
import com.baari.queue.TokenEvents.TokenRecalled;
import com.baari.queue.internal.EventLog;
import com.baari.queue.internal.EventType;
import com.baari.queue.internal.QueueDayEntity;
import com.baari.queue.internal.QueueDayRepository;
import com.baari.queue.internal.TokenEntity;
import com.baari.queue.internal.TokenRef;
import com.baari.queue.internal.TokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that moves a token after it was issued (PLAN §4, §6.2, §6.3): call-next, calling a
 * specific patient, start, complete, no-show, recall, cancel and priority changes.
 *
 * <p>Locking: every action first locks the token's queue day row, then loads the token, always in
 * that order, so two actions can't deadlock (PLAN §6.3). The day lock also serializes call-next,
 * so two doctors of a POOL queue can never pick the same patient and the priority order is always
 * respected (ADR 0004). A token is loaded only after the lock, so the state machine always sees its
 * latest status; {@code @Version} on the token is the extra safety net (409).
 */
@Service
public class TokenActionService {

    private static final Set<TokenStatus> WAITING = EnumSet.of(TokenStatus.WAITING);

    private final QueueAccess access;
    private final QueueDirectory directory;
    private final QueueDayRepository days;
    private final TokenRepository tokens;
    private final EventLog events;
    private final QueueProperties settings;
    private final Clock clock;

    TokenActionService(
            QueueAccess access,
            QueueDirectory directory,
            QueueDayRepository days,
            TokenRepository tokens,
            EventLog events,
            QueueProperties settings,
            Clock clock) {
        this.access = access;
        this.directory = directory;
        this.days = days;
        this.tokens = tokens;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Calls the next patient as decided by {@link NextTokenPolicy} (PLAN §6.2). Works while the
     * day is paused: pausing stops joins, not the doctors.
     *
     * @param doctorId who is calling. A DOCTOR always calls for themselves (leave null); for a
     *     SINGLE queue it defaults to the queue's doctor; for a POOL queue reception must say which
     *     doctor is free.
     * @throws NoOneWaitingException if nobody is waiting
     */
    @Transactional
    public StaffTokenView callNext(StaffPrincipal caller, UUID queueId, UUID doctorId) {
        QueueInfo info = access.requireStaff(caller, queueId);
        UUID doctor = callingDoctor(caller, info, doctorId);
        Instant now = clock.instant();
        QueueDayEntity day =
                days.lockByQueueIdAndServiceDate(queueId, today(info)).orElseThrow(QueueNotOpenedException::new);
        if (day.getStatus() == QueueDayStatus.CLOSED) {
            throw new QueueClosedException();
        }

        List<TokenEntity> waiting = tokens.findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(day.getId(), WAITING);
        Map<UUID, TokenEntity> byId = new HashMap<>();
        waiting.forEach(t -> byId.put(t.getId(), t));
        NextTokenPolicy.Decision decision = NextTokenPolicy.next(
                        new QueueSnapshot(
                                waiting.stream().map(TokenEntity::toTokenView).toList()),
                        new NextTokenPolicy.State(day.getConsecutivePriorityServed()),
                        settings.policySettings())
                .orElseThrow(NoOneWaitingException::new);

        TokenEntity token = byId.get(decision.token().id());
        token.call(doctor, now, false);
        day.recordPolicyState(decision.stateAfter().consecutivePriorityServed());
        long seq = events.append(
                day,
                token.getId(),
                EventType.CALLED,
                ActorType.STAFF,
                caller.userId(),
                now,
                data(token, "doctorId", doctor));
        events.publish(
                new TokenCalled(Ids.newId(), now, day.getClinicId(), day.getId(), seq, token.getId(), doctor, false));
        return view(info, token);
    }

    /**
     * Calls this patient out of order (PLAN §4 {@code CallSpecific}), flagged OVERRIDE_CALL. It
     * doesn't count towards the PRIORITY-in-a-row limit: that counter belongs to call-next.
     */
    @Transactional
    public StaffTokenView call(StaffPrincipal caller, UUID tokenId, UUID doctorId) {
        Locked locked = lock(caller, tokenId);
        UUID doctor = callingDoctor(caller, locked.info(), doctorId);
        Instant now = clock.instant();
        TokenEntity token = locked.token();
        token.call(doctor, now, true);
        long seq = append(locked, caller, EventType.CALLED, now, data(token, "doctorId", doctor));
        events.publish(new TokenCalled(
                Ids.newId(), now, locked.day().getClinicId(), locked.day().getId(), seq, token.getId(), doctor, true));
        return view(locked.info(), token);
    }

    /** CALLED → IN_CONSULTATION, or WAITING → IN_CONSULTATION flagged DIRECT_START. */
    @Transactional
    public StaffTokenView start(StaffPrincipal caller, UUID tokenId) {
        Locked locked = lock(caller, tokenId);
        Instant now = clock.instant();
        TokenEntity token = locked.token();
        boolean direct = token.getStatus() == TokenStatus.WAITING;
        token.start(doctorIfKnown(caller, locked.info()), now);
        long seq = append(
                locked, caller, EventType.CONSULT_STARTED, now, data(token, "doctorId", token.getServedByDoctorId()));
        events.publish(new ConsultStarted(
                Ids.newId(),
                now,
                locked.day().getClinicId(),
                locked.day().getId(),
                seq,
                token.getId(),
                token.getServedByDoctorId(),
                direct));
        return view(locked.info(), token);
    }

    @Transactional
    public StaffTokenView complete(StaffPrincipal caller, UUID tokenId) {
        Locked locked = lock(caller, tokenId);
        Instant now = clock.instant();
        TokenEntity token = locked.token();
        token.complete(now);
        long seq =
                append(locked, caller, EventType.COMPLETED, now, data(token, "doctorId", token.getServedByDoctorId()));
        events.publish(new ConsultCompleted(
                Ids.newId(),
                now,
                locked.day().getClinicId(),
                locked.day().getId(),
                seq,
                token.getId(),
                token.getServedByDoctorId()));
        return view(locked.info(), token);
    }

    /**
     * CALLED → NO_SHOW once {@code no-show-grace-minutes} have passed since the call; earlier only
     * with {@code force}, and then flagged FORCED_NO_SHOW.
     */
    @Transactional
    public StaffTokenView noShow(StaffPrincipal caller, UUID tokenId, boolean force) {
        Locked locked = lock(caller, tokenId);
        Instant now = clock.instant();
        TokenEntity token = locked.token();
        token.markNoShow(now, Duration.ofMinutes(settings.noShowGraceMinutes()), force);
        boolean forced = token.getFlags().contains(TokenFlag.FORCED_NO_SHOW.name());
        long seq = append(locked, caller, EventType.NO_SHOW, now, data(token, "forced", forced));
        events.publish(new TokenNoShow(
                Ids.newId(), now, locked.day().getClinicId(), locked.day().getId(), seq, token.getId(), forced));
        return view(locked.info(), token);
    }

    /**
     * NO_SHOW → WAITING, same day only. The token goes back in line behind
     * {@code recall-insert-offset} waiting NORMAL patients (PLAN §5).
     */
    @Transactional
    public StaffTokenView recall(StaffPrincipal caller, UUID tokenId) {
        Locked locked = lock(caller, tokenId);
        Instant now = clock.instant();
        TokenEntity token = locked.token();
        List<TokenView> waiting =
                tokens
                        .findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(
                                locked.day().getId(), WAITING)
                        .stream()
                        .map(TokenEntity::toTokenView)
                        .toList();
        long sortKey = NextTokenPolicy.recallSortKey(
                new QueueSnapshot(waiting), token.toTokenView(), settings.policySettings());
        token.recall(locked.day().getServiceDate(), today(locked.info()), sortKey);
        long seq = append(locked, caller, EventType.RECALLED, now, data(token, "sortKey", sortKey));
        events.publish(new TokenRecalled(
                Ids.newId(), now, locked.day().getClinicId(), locked.day().getId(), seq, token.getId(), sortKey));
        return view(locked.info(), token);
    }

    /** Staff cancel: from WAITING, CALLED or NO_SHOW. */
    @Transactional
    public StaffTokenView cancel(StaffPrincipal caller, UUID tokenId) {
        Locked locked = lock(caller, tokenId);
        cancel(locked, ActorType.STAFF, caller.userId());
        return view(locked.info(), locked.token());
    }

    /**
     * The patient cancels from their token page; the public code is the proof that it is theirs.
     * Allowed from WAITING or CALLED. An unknown code is "not found".
     */
    @Transactional
    public TokenStatus cancelByPatient(String publicCode) {
        Objects.requireNonNull(publicCode, "publicCode");
        TokenRef ref = tokens.findRefByPublicCode(publicCode).orElseThrow(() -> new NotFoundException("Token"));
        QueueInfo info = directory.find(ref.queueId()).orElseThrow(() -> new NotFoundException("Token"));
        Locked locked = lockAndLoad(info, ref);
        cancel(locked, ActorType.PATIENT, null);
        return locked.token().getStatus();
    }

    /** Only while WAITING. Setting the priority the token already has changes nothing. */
    @Transactional
    public StaffTokenView setPriority(StaffPrincipal caller, UUID tokenId, Priority priority) {
        Objects.requireNonNull(priority, "priority");
        Locked locked = lock(caller, tokenId);
        TokenEntity token = locked.token();
        if (token.getPriority() == priority && token.getStatus() == TokenStatus.WAITING) {
            return view(locked.info(), token);
        }
        Instant now = clock.instant();
        Priority before = token.getPriority();
        token.changePriority(priority);
        long seq = append(
                locked, caller, EventType.PRIORITY_CHANGED, now, Map.of("from", before.name(), "to", priority.name()));
        events.publish(new TokenPriorityChanged(
                Ids.newId(), now, locked.day().getClinicId(), locked.day().getId(), seq, token.getId(), priority));
        return view(locked.info(), token);
    }

    // --- helpers ---

    private void cancel(Locked locked, ActorType by, UUID actorId) {
        Instant now = clock.instant();
        TokenEntity token = locked.token();
        TokenStatus before = token.getStatus();
        token.cancel(by, now);
        long seq = events.append(
                locked.day(),
                token.getId(),
                EventType.CANCELLED,
                by,
                actorId,
                now,
                Map.of("from", before.name(), "to", token.getStatus().name()));
        events.publish(new TokenCancelled(
                Ids.newId(), now, locked.day().getClinicId(), locked.day().getId(), seq, token.getId(), by));
    }

    /** The token's queue day locked, then the token loaded fresh; access checked first. */
    private record Locked(QueueInfo info, QueueDayEntity day, TokenEntity token) {}

    private Locked lock(StaffPrincipal caller, UUID tokenId) {
        TokenRef ref = tokens.findRefById(tokenId).orElseThrow(() -> new NotFoundException("Token"));
        QueueInfo info;
        try {
            info = access.requireStaff(caller, ref.queueId());
        } catch (NotFoundException e) {
            // Another clinic's token, or a queue this doctor isn't on: same answer as a wrong id.
            throw new NotFoundException("Token");
        }
        return lockAndLoad(info, ref);
    }

    private Locked lockAndLoad(QueueInfo info, TokenRef ref) {
        QueueDayEntity day = days.lockById(ref.queueDayId()).orElseThrow();
        TokenEntity token = tokens.findById(ref.tokenId()).orElseThrow();
        return new Locked(info, day, token);
    }

    private long append(Locked locked, StaffPrincipal caller, EventType type, Instant now, Map<String, ?> data) {
        return events.append(locked.day(), locked.token().getId(), type, ActorType.STAFF, caller.userId(), now, data);
    }

    /** Event data: the new status, plus one extra fact. Never names or phone numbers. */
    private static Map<String, Object> data(TokenEntity token, String key, Object value) {
        Map<String, Object> data = new HashMap<>();
        data.put("to", token.getStatus().name());
        if (value != null) {
            data.put(key, value);
        }
        return data;
    }

    /**
     * Who is calling. A doctor calls for themselves; otherwise the request names the doctor, or a
     * SINGLE queue's only doctor is used. The doctor must be assigned to the queue.
     */
    private static UUID callingDoctor(StaffPrincipal caller, QueueInfo info, UUID requested) {
        Set<UUID> assigned = info.queue().doctorIds();
        UUID doctor;
        if (caller.role() == StaffRole.DOCTOR) {
            doctor = caller.doctorId();
            if (requested != null && !requested.equals(doctor)) {
                throw new InvalidRequestException("A doctor can only call patients for themselves.");
            }
        } else if (requested != null) {
            doctor = requested;
        } else if (info.queue().mode() == QueueMode.SINGLE && assigned.size() == 1) {
            doctor = assigned.iterator().next();
        } else if (info.queue().mode() == QueueMode.SINGLE) {
            throw new InvalidRequestException("No doctor is assigned to this queue.");
        } else {
            throw new InvalidRequestException("Choose which doctor is calling.");
        }
        if (!assigned.contains(doctor)) {
            throw new InvalidRequestException("This doctor isn't assigned to this queue.");
        }
        return doctor;
    }

    /** For a direct start: the doctor if it is obvious (a doctor's own login, or a SINGLE queue). */
    private static UUID doctorIfKnown(StaffPrincipal caller, QueueInfo info) {
        if (caller.role() == StaffRole.DOCTOR) {
            return caller.doctorId();
        }
        Set<UUID> assigned = info.queue().doctorIds();
        if (info.queue().mode() == QueueMode.SINGLE && assigned.size() == 1) {
            return assigned.iterator().next();
        }
        return null;
    }

    private LocalDate today(QueueInfo info) {
        return clock.instant().atZone(info.timezone()).toLocalDate();
    }

    private static StaffTokenView view(QueueInfo info, TokenEntity token) {
        return new StaffTokenView(
                token.getId(),
                new DisplayCode(info.queue().displayCodePrefix(), token.getNumber()).toString(),
                token.getNumber(),
                token.getStatus(),
                token.getPriority(),
                token.getVisitType(),
                token.getSource(),
                token.getPatientName(),
                token.getPhoneLast4(),
                token.getFlags().stream().map(TokenFlag::valueOf).toList(),
                token.getServedByDoctorId(),
                token.getJoinedAt(),
                token.getCalledAt(),
                token.getConsultStartedAt(),
                token.getCompletedAt(),
                token.getCancelledAt());
    }
}
