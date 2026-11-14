package com.baari.clinic;

import com.baari.common.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new clinic together with its first CLINIC_ADMIN login. Without that first admin nobody could
 * manage the clinic: the platform admin has no clinic of its own.
 */
public record NewClinic(
        @NotBlank @Size(max = 60) @Pattern(regexp = ValidationPatterns.SLUG)
        String slug,

        @NotBlank @Size(max = 120) String name,
        @NotBlank String timezone,

        @NotBlank @Pattern(regexp = ValidationPatterns.USERNAME)
        String adminUsername,

        @NotBlank @Size(min = 8, max = 72) String adminPassword) {

    @Override
    public String toString() {
        // Keep the password out of logs and exception messages.
        return "NewClinic[slug=" + slug + ", name=" + name + ", timezone=" + timezone + ", adminUsername="
                + adminUsername + "]";
    }
}
