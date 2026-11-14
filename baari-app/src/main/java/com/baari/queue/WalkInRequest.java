package com.baari.queue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Reception adding a patient at the desk. Unlike a QR join, staff may set the priority, and the
 * idempotency key is optional (the staff console sends one to make double-clicks harmless).
 */
public record WalkInRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 30) String phone,
        @NotNull VisitType visitType,
        @NotNull Priority priority,
        UUID idempotencyKey) {

    @Override
    public String toString() {
        return "WalkInRequest[visitType=" + visitType + ", priority=" + priority + ", idempotencyKey=" + idempotencyKey
                + "]";
    }
}
