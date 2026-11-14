package com.baari.queue.internal;

import com.baari.queue.TokenFlag;
import com.baari.queue.TokenStatus;
import java.util.Objects;
import java.util.Optional;

/** What {@link TokenStateMachine} decided: move to a status, or refuse and say why. */
sealed interface Transition {

    /**
     * @param to the new status (may equal the old one, e.g. for a priority change)
     * @param flag set when the move took an unusual path that should be recorded on the token
     */
    record Allowed(TokenStatus to, Optional<TokenFlag> flag) implements Transition {
        public Allowed {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(flag, "flag");
        }
    }

    record Rejected(Reason reason) implements Transition {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Reason {
        /** The command is never valid from the current status. */
        ILLEGAL_TRANSITION,
        /** No-show asked for before the grace period ended, without force. */
        GRACE_NOT_ELAPSED,
        /** Recall asked for on a token from another service date. */
        RECALL_OTHER_DAY
    }
}
