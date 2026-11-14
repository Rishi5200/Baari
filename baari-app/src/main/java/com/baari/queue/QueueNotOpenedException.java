package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/** Nobody has opened today's queue day yet, so it can't be paused, closed or joined. */
public class QueueNotOpenedException extends DomainException {

    public QueueNotOpenedException() {
        super("queue-not-opened", HttpStatus.CONFLICT, "Today's queue hasn't been opened yet.");
    }
}
