package com.baari.queue;

import com.baari.clinic.QrMode;
import com.baari.clinic.QueueDirectory;
import com.baari.clinic.QueueInfo;
import com.baari.clinic.RotatingQr;
import com.baari.clinic.StaffPrincipal;
import com.baari.common.Ids;
import com.baari.common.NotFoundException;
import com.baari.common.crypto.PhoneHasher;
import com.baari.common.crypto.PhoneNumber;
import com.baari.queue.TokenEvents.TokenJoined;
import com.baari.queue.internal.EventLog;
import com.baari.queue.internal.EventType;
import com.baari.queue.internal.PublicCodes;
import com.baari.queue.internal.QueueDayEntity;
import com.baari.queue.internal.QueueDayRepository;
import com.baari.queue.internal.TokenEntity;
import com.baari.queue.internal.TokenRepository;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

/**
 * Issues tokens: patients joining by QR and reception adding walk-ins (PLAN §6.1).
 *
 * <p>Each issue is one transaction that first takes the queue day's row lock and then, while
 * holding it, checks the idempotency key, the day's status, the one-active-token-per-phone rule
 * and the open-token limit, and only then takes the next number. Because every check and the
 * number happen under the same lock:
 *
 * <ul>
 *   <li>numbers are unique and gap-free: a request that fails rolls back its number with it;
 *   <li>a retried request finds the token its first attempt created and returns it, without using
 *       up a number;
 *   <li>a join racing with day close either commits first (and close then expires it) or sees the
 *       day closed (PLAN §6.4 test 6).
 * </ul>
 *
 * The unique constraints in the schema stay as the backstop (see {@link #issue}).
 */
@Service
@Validated
public class TokenService {

    private final QueueDirectory directory;
    private final QueueAccess access;
    private final QueueDayRepository days;
    private final TokenRepository tokens;
    private final EventLog events;
    private final PhoneHasher phoneHasher;
    private final QueueProperties settings;
    private final QueueMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transaction;

    TokenService(
            QueueDirectory directory,
            QueueAccess access,
            QueueDayRepository days,
            TokenRepository tokens,
            EventLog events,
            PhoneHasher phoneHasher,
            QueueProperties settings,
            QueueMetrics metrics,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.metrics = metrics;
        this.directory = directory;
        this.access = access;
        this.days = days;
        this.tokens = tokens;
        this.events = events;
        this.phoneHasher = phoneHasher;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * A patient joins today's queue by QR. Refused while the day is paused (423), closed (410) or
     * not opened yet (409), when the phone already holds an active token (409), or when the queue
     * is full (409).
     */
    public IssuedToken join(UUID queueId, @Valid JoinRequest request) {
        QueueInfo info = directory.find(queueId).orElseThrow(() -> new NotFoundException("Queue"));
        NewToken token = new NewToken(
                request.name().strip(),
                parsePhone(request.phone()),
                request.visitType(),
                Priority.NORMAL,
                TokenSource.QR,
                request.consentVersion(),
                request.idempotencyKey(),
                qrCodeValid(info, request));
        return issue(info, token, ActorType.PATIENT, null);
    }

    /**
     * Static queues need no code. Rotating queues accept the TV's current window and the one before
     * it (a patient may scan just before the code changes), signed with the queue's secret.
     */
    private boolean qrCodeValid(QueueInfo info, JoinRequest request) {
        if (info.queue().qrMode() != QrMode.ROTATING) {
            return true;
        }
        if (request.qrWindow() == null || request.qrSignature() == null) {
            return false;
        }
        long current = RotatingQr.window(clock.instant());
        long w = request.qrWindow();
        return (w == current || w == current - 1)
                && directory.qrSignatureMatches(info.queue().id(), w, request.qrSignature());
    }

    /**
     * Reception adds a patient at the desk. Allowed while the day is paused: pausing stops QR joins,
     * but staff can still add someone who is standing at the desk.
     */
    public IssuedToken issueWalkIn(StaffPrincipal caller, UUID queueId, @Valid WalkInRequest request) {
        QueueInfo info = access.requireStaff(caller, queueId);
        NewToken token = new NewToken(
                request.name().strip(),
                parsePhone(request.phone()),
                request.visitType(),
                request.priority(),
                TokenSource.RECEPTION,
                null,
                request.idempotencyKey(),
                true);
        return issue(info, token, ActorType.STAFF, caller.userId());
    }

    /**
     * Runs the issuing transaction. All checks happen under the day's row lock, so a unique
     * constraint violation should never happen; if one does (a race nobody foresaw), the failed
     * transaction has rolled back completely, and running it once more lets the checks see the
     * row that won, turning the violation into the normal answer (same token, or 409).
     */
    private IssuedToken issue(QueueInfo info, NewToken token, ActorType actorType, UUID actorId) {
        try {
            return transaction.execute(status -> issueLocked(info, token, actorType, actorId));
        } catch (DataIntegrityViolationException e) {
            return transaction.execute(status -> issueLocked(info, token, actorType, actorId));
        }
    }

    private IssuedToken issueLocked(QueueInfo info, NewToken request, ActorType actorType, UUID actorId) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(info.timezone()).toLocalDate();
        QueueDayEntity day =
                days.lockByQueueIdAndServiceDate(info.queue().id(), today).orElseThrow(QueueNotOpenedException::new);

        // A retry gets its token back whatever happened since (even if the day is now closed).
        if (request.idempotencyKey() != null) {
            var existing = tokens.findByQueueDayIdAndIdempotencyKey(day.getId(), request.idempotencyKey());
            if (existing.isPresent()) {
                return toIssued(existing.get(), day, info, false);
            }
        }

        // checked after the retry lookup, so a patient whose join is retried after the code changed
        // still gets their token back
        if (!request.qrCodeValid()) {
            throw new QrExpiredException();
        }
        switch (day.getStatus()) {
            case CLOSED -> throw new QueueClosedException();
            case PAUSED -> {
                if (request.source() == TokenSource.QR) {
                    throw new QueuePausedException();
                }
            }
            case OPEN -> {
                // accepting joins
            }
        }

        TokenEntity.Patient patient = patient(request);
        if (patient.phoneHash() != null) {
            tokens.findFirstByQueueDayIdAndPhoneHashAndStatusIn(day.getId(), patient.phoneHash(), TokenStatus.OPEN)
                    .ifPresent(other -> {
                        throw new AlreadyInQueueException(displayCode(info, other));
                    });
        }
        // patients joining by QR: at most N tokens a day per phone in this queue, whatever happened
        // to the earlier ones (reception can always issue a walk-in)
        if (patient.phoneHash() != null
                && request.source() == TokenSource.QR
                && tokens.countByQueueDayIdAndPhoneHash(day.getId(), patient.phoneHash())
                        >= settings.maxJoinsPerPhone()) {
            throw new TooManyJoinsException();
        }
        if (tokens.countByQueueDayIdAndStatusIn(day.getId(), TokenStatus.OPEN) >= settings.maxOpenTokens()) {
            throw new QueueFullException();
        }

        int number = day.nextNumber();
        TokenEntity token = TokenEntity.issue(
                Ids.newId(),
                day.getId(),
                day.getClinicId(),
                number,
                PublicCodes.newCode(),
                patient,
                request.visitType(),
                request.priority(),
                request.source(),
                request.consentVersion(),
                request.idempotencyKey(),
                now);
        // Flush now: the token_event row below is written with plain SQL and references the token.
        tokens.saveAndFlush(token);

        long seq = events.append(
                day,
                token.getId(),
                EventType.JOINED,
                actorType,
                actorId,
                now,
                Map.of(
                        "number", number,
                        "priority", request.priority().name(),
                        "source", request.source().name()));
        events.publish(new TokenJoined(
                Ids.newId(),
                now,
                day.getClinicId(),
                day.getId(),
                seq,
                token.getId(),
                number,
                request.priority(),
                request.visitType(),
                request.source()));
        metrics.tokenIssued(request.source());
        // the day's id travels with every log line of this request (JSON logs, ADR 0014)
        org.slf4j.MDC.put("queueDayId", day.getId().toString());
        return toIssued(token, day, info, true);
    }

    private TokenEntity.Patient patient(NewToken request) {
        PhoneNumber phone = request.phone();
        if (phone == null) {
            return new TokenEntity.Patient(request.name(), null, null, null);
        }
        return new TokenEntity.Patient(request.name(), phone.e164(), phoneHasher.hash(phone), phone.last4());
    }

    /** Position 1 is called next; computed with the same policy call-next uses. */
    private IssuedToken toIssued(TokenEntity token, QueueDayEntity day, QueueInfo info, boolean created) {
        Integer position = null;
        if (token.getStatus() == TokenStatus.WAITING) {
            List<TokenView> waiting = tokens
                    .findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(day.getId(), EnumSet.of(TokenStatus.WAITING))
                    .stream()
                    .map(TokenEntity::toTokenView)
                    .toList();
            List<TokenView> order = NextTokenPolicy.projectOrder(
                    new QueueSnapshot(waiting),
                    new NextTokenPolicy.State(day.getConsecutivePriorityServed()),
                    settings.policySettings());
            position = order.indexOf(token.toTokenView()) + 1;
        }
        return new IssuedToken(
                token.getId(),
                token.getPublicCode(),
                displayCode(info, token).toString(),
                token.getStatus(),
                token.getPriority(),
                position,
                created);
    }

    private static DisplayCode displayCode(QueueInfo info, TokenEntity token) {
        return new DisplayCode(info.queue().displayCodePrefix(), token.getNumber());
    }

    private static PhoneNumber parsePhone(String raw) {
        return raw == null || raw.isBlank() ? null : PhoneNumber.parse(raw);
    }

    /** What both entry points have in common once validated and normalised. */
    private record NewToken(
            String name,
            PhoneNumber phone,
            VisitType visitType,
            Priority priority,
            TokenSource source,
            String consentVersion,
            UUID idempotencyKey,
            boolean qrCodeValid) {}
}
