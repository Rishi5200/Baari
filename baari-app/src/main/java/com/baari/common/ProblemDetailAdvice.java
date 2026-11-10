package com.baari.common;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place that turns exceptions into HTTP error bodies (RFC 9457 ProblemDetail).
 * Spring MVC's own errors (validation, bad JSON, ...) are handled by the parent class.
 */
@RestControllerAdvice
class ProblemDetailAdvice extends ResponseEntityExceptionHandler {

    static final String TYPE_BASE = "https://baari.app/problems/";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + ex.problemSlug()));
        return problem;
    }

    /** Last resort. The client gets no stack trace and no internal message. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
        problem.setType(URI.create(TYPE_BASE + "internal-error"));
        return problem;
    }
}
