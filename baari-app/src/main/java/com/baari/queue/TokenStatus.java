package com.baari.queue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Where a token is in its lifecycle (PLAN §4). Stored as text in {@code token.status}, so the
 * names are part of the schema: renaming one needs a migration.
 */
public enum TokenStatus {
    WAITING,
    CALLED,
    IN_CONSULTATION,
    COMPLETED,
    NO_SHOW,
    CANCELLED,
    EXPIRED;

    /**
     * Statuses of a token still in play for the day: they count towards the open-token limit, block
     * a second token for the same phone (the {@code token_one_active_per_phone} index lists the same
     * four), and are dealt with when the day closes.
     */
    public static final Set<TokenStatus> OPEN =
            Collections.unmodifiableSet(EnumSet.of(WAITING, CALLED, IN_CONSULTATION, NO_SHOW));
}
