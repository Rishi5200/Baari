package com.baari.common;

import org.springframework.http.HttpStatus;

/**
 * The resource does not exist <em>for this caller</em>. Also used when it belongs to another
 * clinic, so a staff user can't probe which ids exist elsewhere (PLAN §9.2).
 */
public class NotFoundException extends DomainException {

    public NotFoundException(String what) {
        super("not-found", HttpStatus.NOT_FOUND, what + " not found.");
    }
}
