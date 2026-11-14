package com.baari.web;

import com.baari.common.DomainException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Turns domain errors from the HTML pages into the {@code error} page with the right status.
 * Ordered before {@code ProblemDetailAdvice} (which answers JSON for the REST APIs) and limited to
 * this package, so browsers get a page and API clients still get RFC 9457 bodies.
 */
@ControllerAdvice(basePackages = "com.baari.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebErrorAdvice {

    @ExceptionHandler(DomainException.class)
    String handleDomain(DomainException ex, HttpServletResponse response, Model model) {
        return errorPage(ex.status(), ex.getMessage(), response, model);
    }

    /** Service-level Bean Validation that the form validation didn't catch first. */
    @ExceptionHandler(ConstraintViolationException.class)
    String handleValidation(HttpServletResponse response, Model model) {
        return errorPage(HttpStatus.BAD_REQUEST, "Some of the values you entered are not valid.", response, model);
    }

    private static String errorPage(HttpStatus status, String message, HttpServletResponse response, Model model) {
        response.setStatus(status.value());
        model.addAttribute("status", status.value());
        model.addAttribute("error", status.getReasonPhrase());
        model.addAttribute("message", message);
        return "error";
    }
}
