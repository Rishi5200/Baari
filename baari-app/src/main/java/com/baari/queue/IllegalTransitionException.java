package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/** The token's current status doesn't allow this action (PLAN §4), e.g. completing a waiting token. */
public class IllegalTransitionException extends DomainException {

    public IllegalTransitionException(String message) {
        super("illegal-transition", HttpStatus.CONFLICT, message);
    }
}
