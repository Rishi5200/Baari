package com.baari.queue;

import com.baari.common.DomainException;
import org.springframework.http.HttpStatus;

/**
 * No-show asked for before {@code no-show-grace-minutes} have passed since the call (PLAN §4).
 * Staff can repeat the request with {@code force=true}.
 */
public class GraceNotElapsedException extends DomainException {

    public GraceNotElapsedException(int graceMinutes) {
        super(
                "grace-not-elapsed",
                HttpStatus.CONFLICT,
                "The patient was called less than " + graceMinutes
                        + " minutes ago. Wait a little longer, or force the no-show.");
    }
}
