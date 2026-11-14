package com.baari.queue;

import java.util.List;

/** The tokens of one queue day, as input to {@link NextTokenPolicy}. Order does not matter. */
public record QueueSnapshot(List<TokenView> tokens) {

    public QueueSnapshot {
        tokens = List.copyOf(tokens);
    }
}
