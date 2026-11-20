package com.baari.eta;

import com.baari.queue.Board;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.Map;
import java.util.UUID;

/**
 * The staff console's board plus the predicted start of each waiting patient. The board's fields
 * stay at the top level of the JSON.
 *
 * @param estimates by token id; only waiting patients have one
 */
public record StaffBoardView(@JsonUnwrapped Board board, Map<UUID, TokenEstimate> estimates) {

    public StaffBoardView {
        estimates = Map.copyOf(estimates);
    }
}
