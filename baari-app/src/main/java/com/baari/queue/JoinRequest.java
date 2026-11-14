package com.baari.queue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A patient joining by QR code (PLAN Appendix A). Patients can't choose a priority; reception sets
 * it for walk-ins or later with "set priority".
 *
 * @param phone optional; any format {@link com.baari.common.crypto.PhoneNumber#parse} accepts
 * @param consentVersion the privacy notice version the patient accepted on the join page
 * @param idempotencyKey generated when the join page renders, so a retried submit returns the same
 *     token instead of a second one
 */
public record JoinRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 30) String phone,
        @NotNull VisitType visitType,
        @NotBlank @Size(max = 20) String consentVersion,
        @NotNull UUID idempotencyKey) {

    /** Names may be logged by accident; the phone number must never be. */
    @Override
    public String toString() {
        return "JoinRequest[visitType=" + visitType + ", idempotencyKey=" + idempotencyKey + "]";
    }
}
