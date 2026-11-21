package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/**
 * This phone number has joined this queue too many times today (PLAN §9.3: 3 per day per queue).
 * Stops one person cancelling and re-joining to hold places, or flooding a queue with one number.
 */
public class TooManyJoinsException extends DomainException {

    public TooManyJoinsException() {
        super(
                "too-many-joins",
                HttpStatus.TOO_MANY_REQUESTS,
                "This phone number has joined this queue too many times today. Please ask at the reception desk.");
    }
}
