package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/** The queue is paused, so patients can't join right now (PLAN Appendix A: 423). */
public class QueuePausedException extends DomainException {

    public QueuePausedException() {
        super("queue-paused", HttpStatus.LOCKED, "This queue is paused. Please try again in a few minutes.");
    }
}
