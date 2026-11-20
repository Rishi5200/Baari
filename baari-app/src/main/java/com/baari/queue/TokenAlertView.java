package com.baari.queue;

import java.util.UUID;

/**
 * What a notification needs to know about a token when it is sent: where it is and what state it is
 * in. No name, phone number or public code.
 *
 * @param displayCode the code the patient knows, for example {@code A-017}
 */
public record TokenAlertView(UUID tokenId, UUID queueId, UUID queueDayId, String displayCode, TokenStatus status) {}
