package com.baari.queue;

import java.util.Objects;

/**
 * The code shown on the TV and called out loud: the queue's prefix and the token number, padded
 * to three digits ({@code A-017}). Numbers above 999 simply get longer ({@code A-1000}).
 */
public record DisplayCode(String prefix, int number) {

    public DisplayCode {
        Objects.requireNonNull(prefix, "prefix");
        if (number < 1) {
            throw new IllegalArgumentException("number must be >= 1");
        }
    }

    @Override
    public String toString() {
        return "%s-%03d".formatted(prefix, number);
    }
}
