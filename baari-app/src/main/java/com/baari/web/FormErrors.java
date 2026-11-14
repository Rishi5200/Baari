package com.baari.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

/**
 * Field name → first error message, for templates. Our form objects are records, so templates read
 * values with {@code form.name()} and errors from this map instead of {@code th:field}, which
 * expects JavaBean getters.
 */
final class FormErrors {

    private FormErrors() {}

    static Map<String, String> of(BindingResult result) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : result.getFieldErrors()) {
            // A type mismatch (e.g. an unknown enum value) has a technical message; keep it simple.
            String message = error.isBindingFailure() ? "is not a valid value" : error.getDefaultMessage();
            errors.putIfAbsent(error.getField(), message);
        }
        return errors;
    }
}
