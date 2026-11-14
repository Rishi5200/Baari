package com.baari.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@Tag("unit")
class ProblemDetailAdviceTest {

    private final ProblemDetailAdvice advice = new ProblemDetailAdvice();

    private static final class TestProblem extends DomainException {
        TestProblem() {
            super("illegal-transition", HttpStatus.CONFLICT, "Cannot do that now.");
        }
    }

    @Test
    void domainExceptionBecomesProblemDetailWithTypeUri() {
        ProblemDetail problem = advice.handleDomain(new TestProblem());

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(URI.create("https://baari.app/problems/illegal-transition"));
        assertThat(problem.getDetail()).isEqualTo("Cannot do that now.");
    }

    private record Login(
            @NotBlank String username, @Size(min = 8) String password) {}

    @Test
    void validationFailureListsFieldsButNotRejectedValues() {
        Set<ConstraintViolation<Login>> violations;
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            violations = factory.getValidator().validate(new Login("", "hunter2"));
        }

        ProblemDetail problem = advice.handleConstraintViolation(new ConstraintViolationException(violations));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getType()).isEqualTo(URI.create("https://baari.app/problems/validation"));
        assertThat(problem.getDetail()).contains("username:", "password:").doesNotContain("hunter2");
    }

    @Test
    void unexpectedExceptionHidesInternalMessage() {
        ProblemDetail problem = advice.handleUnexpected(new IllegalStateException("db password is hunter2"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getType()).isEqualTo(URI.create("https://baari.app/problems/internal-error"));
        assertThat(problem.getDetail()).doesNotContain("hunter2");
    }
}
