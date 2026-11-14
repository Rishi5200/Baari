package com.baari.clinic;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is calling a staff service. Built by the identity module from the logged-in session, never
 * from request data: {@code clinicId} must come from here, not from a URL or form (PLAN §9.2).
 *
 * <p>It lives in {@code clinic} rather than {@code identity} because the clinic services take it
 * as a parameter, and identity already depends on clinic (the reverse would be a cycle).
 */
public record StaffPrincipal(UUID userId, String username, StaffRole role, UUID clinicId, UUID doctorId)
        implements Serializable {

    // Stored inside the HTTP session, which lives in Redis (Java serialization).
    @Serial
    private static final long serialVersionUID = 1L;

    public StaffPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");
    }

    public static StaffPrincipal of(StaffUserView user) {
        return new StaffPrincipal(user.id(), user.username(), user.role(), user.clinicId(), user.doctorId());
    }
}
