package com.baari.clinic;

/** Staff roles (PLAN §9.2). PLATFORM_ADMIN is the only role without a clinic. */
public enum StaffRole {
    PLATFORM_ADMIN,
    CLINIC_ADMIN,
    DOCTOR,
    RECEPTIONIST;

    public boolean belongsToClinic() {
        return this != PLATFORM_ADMIN;
    }
}
