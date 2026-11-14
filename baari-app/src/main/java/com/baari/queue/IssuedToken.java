package com.baari.queue;

import java.util.UUID;

/**
 * The result of a join or walk-in. ETA fields arrive in Phase 4.
 *
 * @param publicCode the secret for the patient's token page; only ever returned to the request that
 *     created the token (or its retry with the same idempotency key)
 * @param position 1 = called next; null when the token is no longer waiting (possible on a retry)
 * @param created false when an earlier request with the same idempotency key already made it
 */
public record IssuedToken(
        UUID tokenId,
        String publicCode,
        String displayCode,
        TokenStatus status,
        Priority priority,
        Integer position,
        boolean created) {}
