package com.baari.common;

import org.springframework.http.HttpStatus;

/** Well-formed input that breaks a business rule, e.g. a SINGLE queue with two doctors. */
public class InvalidRequestException extends DomainException {

    public InvalidRequestException(String detail) {
        super("invalid-request", HttpStatus.BAD_REQUEST, detail);
    }
}
