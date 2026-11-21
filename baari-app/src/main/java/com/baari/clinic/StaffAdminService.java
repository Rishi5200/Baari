package com.baari.clinic;

import com.baari.clinic.internal.DoctorRepository;
import com.baari.clinic.internal.StaffUserEntity;
import com.baari.clinic.internal.StaffUserRepository;
import com.baari.common.DuplicateException;
import com.baari.common.ForbiddenException;
import com.baari.common.Ids;
import com.baari.common.InvalidRequestException;
import com.baari.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Staff logins of the caller's clinic. Everything here needs CLINIC_ADMIN. */
@Service
@Validated
public class StaffAdminService {

    private final StaffUserRepository staff;
    private final DoctorRepository doctors;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    StaffAdminService(
            StaffUserRepository staff, DoctorRepository doctors, PasswordEncoder passwordEncoder, Clock clock) {
        this.staff = staff;
        this.doctors = doctors;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StaffUserView> list(StaffPrincipal caller) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        return staff.findAllByClinicIdOrderByUsername(clinicId).stream()
                .map(StaffUserEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffUserView get(StaffPrincipal caller, UUID userId) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        return find(userId, clinicId).toView();
    }

    @Transactional
    public StaffUserView create(StaffPrincipal caller, @Valid NewStaffUser request) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        checkRole(clinicId, request.role(), request.doctorId());
        if (staff.existsByUsername(request.username())) {
            throw new DuplicateException("This username is taken.");
        }
        return staff.save(new StaffUserEntity(
                        Ids.newId(),
                        clinicId,
                        request.username(),
                        passwordEncoder.encode(request.password()),
                        request.role(),
                        request.doctorId(),
                        clock.instant()))
                .toView();
    }

    @Transactional
    public StaffUserView changeRole(StaffPrincipal caller, UUID userId, @NotNull StaffRole role, UUID doctorId) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        StaffUserEntity user = find(userId, clinicId);
        checkRole(clinicId, role, doctorId);
        if (role != StaffRole.CLINIC_ADMIN) {
            guardLastAdmin(caller, user, clinicId, "change the role of");
        }
        user.changeRole(role, doctorId);
        return user.toView();
    }

    @Transactional
    public void resetPassword(StaffPrincipal caller, UUID userId, @NotBlank @Size(min = 8, max = 72) String password) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        find(userId, clinicId).changePasswordHash(passwordEncoder.encode(password));
    }

    @Transactional
    public StaffUserView setEnabled(StaffPrincipal caller, UUID userId, boolean enabled) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        StaffUserEntity user = find(userId, clinicId);
        if (!enabled) {
            guardLastAdmin(caller, user, clinicId, "disable");
        }
        user.setEnabled(enabled);
        return user.toView();
    }

    /**
     * Turns the entity's invariants into proper HTTP errors before we touch it. Granting
     * PLATFORM_ADMIN is privilege escalation, hence 403 rather than 400.
     */
    private void checkRole(UUID clinicId, StaffRole role, UUID doctorId) {
        if (role == StaffRole.PLATFORM_ADMIN) {
            throw new ForbiddenException();
        }
        if (role == StaffRole.DOCTOR) {
            if (doctorId == null) {
                throw new InvalidRequestException("A DOCTOR login must be linked to a doctor.");
            }
            doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> new NotFoundException("Doctor"));
        } else if (doctorId != null) {
            throw new InvalidRequestException("Only a DOCTOR login can be linked to a doctor.");
        }
    }

    /**
     * Stops a change that takes an active clinic admin away (ADR 0009 item 1): an admin may not lock
     * themselves out, and the clinic's last active admin stays.
     */
    private void guardLastAdmin(StaffPrincipal caller, StaffUserEntity user, UUID clinicId, String action) {
        if (user.getRole() != StaffRole.CLINIC_ADMIN || !user.isEnabled()) {
            return; // not an active admin: nothing is taken away
        }
        if (user.getId().equals(caller.userId())) {
            throw new LastAdminException("You cannot " + action + " your own admin account. Ask another admin.");
        }
        if (staff.findAllByClinicIdAndRoleAndEnabledTrue(clinicId, StaffRole.CLINIC_ADMIN)
                        .size()
                <= 1) {
            throw new LastAdminException("This is the clinic's last active admin. Make someone else an admin first.");
        }
    }

    private StaffUserEntity find(UUID userId, UUID clinicId) {
        return staff.findByIdAndClinicId(userId, clinicId).orElseThrow(() -> new NotFoundException("Staff user"));
    }
}
