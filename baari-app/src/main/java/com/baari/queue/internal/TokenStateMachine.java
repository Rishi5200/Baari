package com.baari.queue.internal;

import static com.baari.queue.TokenStatus.CALLED;
import static com.baari.queue.TokenStatus.COMPLETED;
import static com.baari.queue.TokenStatus.EXPIRED;
import static com.baari.queue.TokenStatus.IN_CONSULTATION;
import static com.baari.queue.TokenStatus.NO_SHOW;
import static com.baari.queue.TokenStatus.WAITING;

import com.baari.queue.ActorType;
import com.baari.queue.TokenFlag;
import com.baari.queue.TokenStatus;
import com.baari.queue.internal.TokenCommand.CallNext;
import com.baari.queue.internal.TokenCommand.CallSpecific;
import com.baari.queue.internal.TokenCommand.Cancel;
import com.baari.queue.internal.TokenCommand.CloseDay;
import com.baari.queue.internal.TokenCommand.Complete;
import com.baari.queue.internal.TokenCommand.MarkNoShow;
import com.baari.queue.internal.TokenCommand.Recall;
import com.baari.queue.internal.TokenCommand.SetPriority;
import com.baari.queue.internal.TokenCommand.Start;
import com.baari.queue.internal.Transition.Allowed;
import com.baari.queue.internal.Transition.Reason;
import com.baari.queue.internal.Transition.Rejected;
import java.time.Duration;
import java.util.Optional;

/**
 * The only place that decides which token status changes are allowed (PLAN §4). Pure: no clock,
 * no database, no Spring. Callers pass in every fact the rules need (see {@link TokenCommand}).
 *
 * <p>The service layer applies the result, writes the {@code token_event} row and the outbox
 * event, and turns a {@link Rejected} into an HTTP error.
 */
final class TokenStateMachine {

    private TokenStateMachine() {}

    static Transition apply(TokenStatus from, TokenCommand command) {
        return switch (command) {
            case CallNext c -> from == WAITING ? allow(CALLED) : illegal();
            case CallSpecific c -> from == WAITING ? allow(CALLED, TokenFlag.OVERRIDE_CALL) : illegal();
            case Start s -> start(from);
            case Complete c -> from == IN_CONSULTATION ? allow(COMPLETED) : illegal();
            case MarkNoShow n -> noShow(from, n);
            case Recall r -> recall(from, r);
            case Cancel c -> cancel(from, c.by());
            case SetPriority p -> from == WAITING ? allow(WAITING) : illegal();
            case CloseDay d -> closeDay(from);
        };
    }

    /** Normally from CALLED; WAITING is allowed for a patient who walked straight in. */
    private static Transition start(TokenStatus from) {
        return switch (from) {
            case CALLED -> allow(IN_CONSULTATION);
            case WAITING -> allow(IN_CONSULTATION, TokenFlag.DIRECT_START);
            default -> illegal();
        };
    }

    /**
     * Only after the grace period, so a patient walking back from the pharmacy isn't dropped.
     * Staff may force it earlier; the token is flagged so it shows up in audits.
     */
    private static Transition noShow(TokenStatus from, MarkNoShow command) {
        if (from != CALLED) {
            return illegal();
        }
        Duration waited = Duration.between(command.calledAt(), command.now());
        if (waited.compareTo(command.grace()) >= 0) {
            return allow(NO_SHOW);
        }
        return command.force() ? allow(NO_SHOW, TokenFlag.FORCED_NO_SHOW) : new Rejected(Reason.GRACE_NOT_ELAPSED);
    }

    /** Back to WAITING, same day only: yesterday's queue is gone. */
    private static Transition recall(TokenStatus from, Recall command) {
        if (from != NO_SHOW) {
            return illegal();
        }
        return command.serviceDate().equals(command.today()) ? allow(WAITING) : new Rejected(Reason.RECALL_OTHER_DAY);
    }

    /**
     * A patient can leave while waiting or after being called. Staff can also clear a no-show.
     * The system never cancels: it expires tokens through {@link CloseDay}.
     */
    private static Transition cancel(TokenStatus from, ActorType by) {
        boolean allowed = switch (by) {
            case PATIENT -> from == WAITING || from == CALLED;
            case STAFF -> from == WAITING || from == CALLED || from == NO_SHOW;
            case SYSTEM -> false;
        };
        return allowed ? allow(TokenStatus.CANCELLED) : illegal();
    }

    /**
     * Open tokens expire. A consultation in progress did happen, so it completes at close time
     * and is flagged so the ETA training data can leave it out.
     */
    private static Transition closeDay(TokenStatus from) {
        return switch (from) {
            case WAITING, CALLED, NO_SHOW -> allow(EXPIRED);
            case IN_CONSULTATION -> allow(COMPLETED, TokenFlag.AUTO_CLOSED);
            default -> illegal();
        };
    }

    private static Transition allow(TokenStatus to) {
        return new Allowed(to, Optional.empty());
    }

    private static Transition allow(TokenStatus to, TokenFlag flag) {
        return new Allowed(to, Optional.of(flag));
    }

    private static Transition illegal() {
        return new Rejected(Reason.ILLEGAL_TRANSITION);
    }
}
