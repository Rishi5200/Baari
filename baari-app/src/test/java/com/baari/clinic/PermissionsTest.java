package com.baari.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.common.ForbiddenException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("unit")
class PermissionsTest {

    private static final UUID CLINIC = UUID.randomUUID();

    private static StaffPrincipal as(StaffRole role) {
        UUID clinic = role.belongsToClinic() ? CLINIC : null;
        UUID doctor = role == StaffRole.DOCTOR ? UUID.randomUUID() : null;
        return new StaffPrincipal(UUID.randomUUID(), "u", role, clinic, doctor);
    }

    @ParameterizedTest
    @EnumSource(value = StaffRole.class, names = "PLATFORM_ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void onlyPlatformAdminPassesPlatformCheck(StaffRole role) {
        Permissions.requirePlatformAdmin(as(StaffRole.PLATFORM_ADMIN));
        assertThatThrownBy(() -> Permissions.requirePlatformAdmin(as(role))).isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StaffRole.class, names = "CLINIC_ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void onlyClinicAdminPassesAdminCheck(StaffRole role) {
        assertThat(Permissions.requireClinicAdmin(as(StaffRole.CLINIC_ADMIN))).isEqualTo(CLINIC);
        assertThatThrownBy(() -> Permissions.requireClinicAdmin(as(role))).isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StaffRole.class, names = "PLATFORM_ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void everyClinicRolePassesMemberCheck(StaffRole role) {
        assertThat(Permissions.requireClinicMember(as(role))).isEqualTo(CLINIC);
    }

    @Test
    void platformAdminIsNotAClinicMember() {
        assertThatThrownBy(() -> Permissions.requireClinicMember(as(StaffRole.PLATFORM_ADMIN)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void clinicRoleWithoutClinicIsRejectedNotTreatedAsAllClinics() {
        var broken = new StaffPrincipal(UUID.randomUUID(), "u", StaffRole.CLINIC_ADMIN, null, null);

        assertThatThrownBy(() -> Permissions.requireClinicAdmin(broken)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Permissions.requireClinicMember(broken)).isInstanceOf(ForbiddenException.class);
    }
}
