package com.baari.clinic.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.clinic.StaffRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class StaffUserEntityTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-14T04:30:00Z");

    private static StaffUserEntity user(UUID clinicId, StaffRole role, UUID doctorId) {
        return new StaffUserEntity(UUID.randomUUID(), clinicId, "u", "hash", role, doctorId, T0);
    }

    @Test
    void validCombinationsAreAccepted() {
        assertThat(user(null, StaffRole.PLATFORM_ADMIN, null).getClinicId()).isNull();
        assertThat(user(CLINIC, StaffRole.CLINIC_ADMIN, null).getRole()).isEqualTo(StaffRole.CLINIC_ADMIN);
        assertThat(user(CLINIC, StaffRole.RECEPTIONIST, null).isEnabled()).isTrue();
        assertThat(user(CLINIC, StaffRole.DOCTOR, DOCTOR).getDoctorId()).isEqualTo(DOCTOR);
    }

    @Test
    void platformAdminMustNotHaveClinic() {
        assertThatThrownBy(() -> user(CLINIC, StaffRole.PLATFORM_ADMIN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clinicRolesMustHaveClinic() {
        assertThatThrownBy(() -> user(null, StaffRole.CLINIC_ADMIN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user(null, StaffRole.RECEPTIONIST, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user(null, StaffRole.DOCTOR, DOCTOR)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doctorMustBeLinkedToDoctorAndOthersMustNot() {
        assertThatThrownBy(() -> user(CLINIC, StaffRole.DOCTOR, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user(CLINIC, StaffRole.RECEPTIONIST, DOCTOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeRoleKeepsInvariants() {
        StaffUserEntity u = user(CLINIC, StaffRole.RECEPTIONIST, null);

        u.changeRole(StaffRole.DOCTOR, DOCTOR);
        assertThat(u.getRole()).isEqualTo(StaffRole.DOCTOR);

        assertThatThrownBy(() -> u.changeRole(StaffRole.PLATFORM_ADMIN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> u.changeRole(StaffRole.CLINIC_ADMIN, DOCTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(u.getRole()).isEqualTo(StaffRole.DOCTOR);
    }
}
