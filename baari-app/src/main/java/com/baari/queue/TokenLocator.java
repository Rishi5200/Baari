package com.baari.queue;

import java.util.UUID;

/** Which token and queue a public code belongs to. Holds no patient details. */
public record TokenLocator(UUID tokenId, UUID queueId, UUID clinicId) {}
