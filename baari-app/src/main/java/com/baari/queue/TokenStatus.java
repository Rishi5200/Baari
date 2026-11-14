package com.baari.queue;

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
    EXPIRED
}
