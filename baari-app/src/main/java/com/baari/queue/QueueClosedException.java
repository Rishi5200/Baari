package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/** Today's queue day is closed; it can't be reopened, joined or paused (PLAN Appendix A: 410). */
public class QueueClosedException extends DomainException {

    public QueueClosedException() {
        super("queue-closed", HttpStatus.GONE, "This queue is closed for today.");
    }
}
