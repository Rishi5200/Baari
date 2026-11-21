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
        @NotNull UUID idempotencyKey,
        Long qrWindow,
        @Size(max = 64) String qrSignature) {

    /** A join from a static QR code (poster) or the API without a rotating code. */
    public JoinRequest(String name, String phone, VisitType visitType, String consentVersion, UUID idempotencyKey) {
        this(name, phone, visitType, consentVersion, idempotencyKey, null, null);
    }

    /** Names may be logged by accident; the phone number must never be. */
    @Override
    public String toString() {
        return "JoinRequest[visitType=" + visitType + ", idempotencyKey=" + idempotencyKey + "]";
    }
}
