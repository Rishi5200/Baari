package com.baari.queue;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The part of a token that queue ordering needs (PLAN §5). No patient details, so it can be
 * handed to other modules (ETA projection, live updates) freely.
 *
 * @param number the number on the display code ({@code A-017} is 17)
 * @param sortKey place in line; {@code number * 1000} unless the token was recalled
 */
public record TokenView(UUID id, int number, Priority priority, TokenStatus status, long sortKey, Instant joinedAt) {

    public TokenView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }

    /** The sort key a newly issued token gets; the gaps leave room for recalls. */
    public static long initialSortKey(int number) {
        return number * 1000L;
    }
}
