package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/**
 * This phone number already holds an active token in today's queue (PLAN §6.1). The message names
 * only the display code: returning the other token's public code would let anyone who knows a
 * phone number take over that patient's token.
 */
public class AlreadyInQueueException extends DomainException {

    private final String displayCode;

    public AlreadyInQueueException(DisplayCode displayCode) {
        super("already-in-queue", HttpStatus.CONFLICT, "You already hold token " + displayCode + " in this queue.");
        this.displayCode = displayCode.toString();
    }

    public String displayCode() {
        return displayCode;
    }
}
