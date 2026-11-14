package com.baari.clinic;

import com.baari.common.ForbiddenException;
import java.util.UUID;

/**
 * Role checks for the admin services, in one place so the rules are easy to read and test.
 *
 * <ul>
 *   <li>Creating clinics: PLATFORM_ADMIN.
 *   <li>Managing doctors, queues and staff of a clinic: CLINIC_ADMIN of that clinic.
 *   <li>Reading doctors and queues: any staff member of that clinic (consoles need them).
 * </ul>
 *
 * Each check returns the caller's clinic id, so services scope their queries with the id that
 * came from the session.
 */
final class Permissions {

    private Permissions() {}

    static void requirePlatformAdmin(StaffPrincipal p) {
        if (p.role() != StaffRole.PLATFORM_ADMIN) {
            throw new ForbiddenException();
        }
    }

    static UUID requireClinicAdmin(StaffPrincipal p) {
        if (p.role() != StaffRole.CLINIC_ADMIN) {
            throw new ForbiddenException();
        }
        return clinicOf(p);
    }

    static UUID requireClinicMember(StaffPrincipal p) {
        if (!p.role().belongsToClinic()) {
            throw new ForbiddenException();
        }
        return clinicOf(p);
    }

    private static UUID clinicOf(StaffPrincipal p) {
        if (p.clinicId() == null) {
            // Can't happen for a user loaded from the database (StaffUserEntity enforces it), but a
            // missing tenant must never silently become "all tenants".
            throw new ForbiddenException();
        }
        return p.clinicId();
    }
}
