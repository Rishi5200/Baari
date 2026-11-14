package com.baari.common;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
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

    /**
     * Bean Validation failures on {@code @Validated} services. Lists the failing fields and
     * messages, never the rejected values (they may be personal data or passwords).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> lastNode(v.getPropertyPath().toString()) + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create(TYPE_BASE + "validation"));
        return problem;
    }

    /**
     * A {@code @Version} conflict: someone else changed the same row first (a double-click, or two
     * staff acting on one token). The client should reload and try again (PLAN §6.3).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Someone else changed this at the same time. Reload and try again.");
        problem.setType(URI.create(TYPE_BASE + "concurrent-modification"));
        return problem;
    }

    /** "createDoctor.details.name" → "name". */
    private static String lastNode(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
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
