package com.baari.clinic;

import com.baari.clinic.internal.ClinicEntity;
import com.baari.clinic.internal.ClinicRepository;
import com.baari.clinic.internal.StaffUserEntity;
import com.baari.clinic.internal.StaffUserRepository;
import com.baari.common.DuplicateException;
import com.baari.common.Ids;
import com.baari.common.InvalidRequestException;
import com.baari.common.NotFoundException;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Clinic (tenant) management. Creating clinics is for the platform admin only. */
@Service
@Validated
public class ClinicAdminService {

    private final ClinicRepository clinics;
    private final StaffUserRepository staff;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    ClinicAdminService(
            ClinicRepository clinics, StaffUserRepository staff, PasswordEncoder passwordEncoder, Clock clock) {
        this.clinics = clinics;
        this.staff = staff;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Creates the clinic and its first CLINIC_ADMIN in one transaction, so there is never a clinic
     * nobody can manage. The exists-checks give friendly errors; the unique constraints in the
     * database still protect against two concurrent requests.
     */
    @Transactional
    public ClinicView createClinic(StaffPrincipal caller, @Valid NewClinic request) {
        Permissions.requirePlatformAdmin(caller);
        ZoneId zone = parseZone(request.timezone());
        if (clinics.existsBySlug(request.slug())) {
            throw new DuplicateException("A clinic with this slug already exists.");
        }
        if (staff.existsByUsername(request.adminUsername())) {
            throw new DuplicateException("This username is taken.");
        }
        var now = clock.instant();
        ClinicEntity clinic = clinics.save(new ClinicEntity(Ids.newId(), request.slug(), request.name(), zone, now));
        staff.save(new StaffUserEntity(
                Ids.newId(),
                clinic.getId(),
                request.adminUsername(),
                passwordEncoder.encode(request.adminPassword()),
                StaffRole.CLINIC_ADMIN,
                null,
                now));
        return clinic.toView();
    }

    @Transactional(readOnly = true)
    public List<ClinicView> listClinics(StaffPrincipal caller) {
        Permissions.requirePlatformAdmin(caller);
        return clinics.findAll().stream().map(ClinicEntity::toView).toList();
    }

    /** The caller's own clinic. */
    @Transactional(readOnly = true)
    public ClinicView myClinic(StaffPrincipal caller) {
        var clinicId = Permissions.requireClinicMember(caller);
        return clinics.findById(clinicId).map(ClinicEntity::toView).orElseThrow(() -> new NotFoundException("Clinic"));
    }

    private static ZoneId parseZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidRequestException("Unknown time zone: " + timezone);
        }
    }
}
