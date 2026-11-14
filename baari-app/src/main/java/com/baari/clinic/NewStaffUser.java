package com.baari.clinic;

import com.baari.common.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A new staff login in the caller's clinic. {@code doctorId} is required for DOCTOR and must be
 * null otherwise. Password max 72: BCrypt ignores anything after 72 bytes.
 */
public record NewStaffUser(
        @NotBlank @Pattern(regexp = ValidationPatterns.USERNAME)
        String username,

        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull StaffRole role,
        UUID doctorId) {

    @Override
    public String toString() {
        return "NewStaffUser[username=" + username + ", role=" + role + ", doctorId=" + doctorId + "]";
    }
}
