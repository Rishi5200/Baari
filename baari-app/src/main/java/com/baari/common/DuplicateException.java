package com.baari.common;

import org.springframework.http.HttpStatus;

/** A unique value (clinic slug, queue slug, username) is already taken. */
public class DuplicateException extends DomainException {

    public DuplicateException(String detail) {
        super("duplicate", HttpStatus.CONFLICT, detail);
    }
}
