package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/** Call-next found no waiting patient (PLAN §6.4 test 4: the losing doctor of a race gets this). */
public class NoOneWaitingException extends DomainException {

    public NoOneWaitingException() {
        super("no-one-waiting", HttpStatus.CONFLICT, "No one is waiting.");
    }
}
