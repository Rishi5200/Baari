package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/** Today's queue already holds {@code baari.queue.max-open-tokens} open tokens. */
public class QueueFullException extends DomainException {

    public QueueFullException() {
        super("queue-full", HttpStatus.CONFLICT, "This queue is full for today. Please ask at reception.");
    }
}
