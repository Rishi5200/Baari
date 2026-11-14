package com.baari.clinic;

import java.util.UUID;

/**
 * Read model of a staff user. No password hash: that only leaves the clinic module through the
 * login lookup used by Spring Security. {@code clinicId} is null only for PLATFORM_ADMIN;
 * {@code doctorId} is set only for DOCTOR.
 */
public record StaffUserView(UUID id, UUID clinicId, String username, StaffRole role, UUID doctorId, boolean enabled) {}
