package com.baari.queue.internal;

import java.util.UUID;

/**
 * Where a token lives: enough to check access and lock its queue day before the token itself is
 * loaded. A plain projection, so the token entity isn't loaded (and cached) before the lock.
 */
public record TokenRef(UUID tokenId, UUID queueDayId, UUID queueId, UUID clinicId) {}
