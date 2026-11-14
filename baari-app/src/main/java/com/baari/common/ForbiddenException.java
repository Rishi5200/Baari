package com.baari.common;

import org.springframework.http.HttpStatus;

/** The caller is authenticated but their role may not do this. */
public class ForbiddenException extends DomainException {

    public ForbiddenException() {
        super("forbidden", HttpStatus.FORBIDDEN, "You are not allowed to do this.");
    }
}
