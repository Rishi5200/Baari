package com.baari.common;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected business errors (TokenNotFound, IllegalTransition, QueueClosed, ...).
 * Controllers never build error bodies; {@link ProblemDetailAdvice} maps these to RFC 9457.
 */
public abstract class DomainException extends RuntimeException {

    private final String problemSlug;
    private final HttpStatus status;

    protected DomainException(String problemSlug, HttpStatus status, String message) {
        super(message);
        this.problemSlug = problemSlug;
        this.status = status;
    }

    /** Last path segment of the problem type URI, e.g. "illegal-transition". */
    public String problemSlug() {
        return problemSlug;
    }

    public HttpStatus status() {
        return status;
    }
}
