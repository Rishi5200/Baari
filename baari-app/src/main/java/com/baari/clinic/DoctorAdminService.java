package com.baari.clinic;

import com.baari.clinic.internal.DoctorEntity;
import com.baari.clinic.internal.DoctorRepository;
import com.baari.common.Ids;
import com.baari.common.NotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * Doctors of the caller's clinic. Writes need CLINIC_ADMIN; reads are open to all staff of the
 * clinic. Doctors are deactivated rather than deleted.
 */
@Service
@Validated
public class DoctorAdminService {

    private final DoctorRepository doctors;

    DoctorAdminService(DoctorRepository doctors) {
        this.doctors = doctors;
    }

    @Transactional(readOnly = true)
    public List<DoctorView> list(StaffPrincipal caller) {
        UUID clinicId = Permissions.requireClinicMember(caller);
        return doctors.findAllByClinicIdOrderByName(clinicId).stream()
                .map(DoctorEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public DoctorView get(StaffPrincipal caller, UUID doctorId) {
        UUID clinicId = Permissions.requireClinicMember(caller);
        return find(doctorId, clinicId).toView();
    }

    @Transactional
    public DoctorView create(StaffPrincipal caller, @Valid DoctorDetails details) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        return doctors.save(new DoctorEntity(Ids.newId(), clinicId, details.name(), details.roomLabel()))
                .toView();
    }

    @Transactional
    public DoctorView update(StaffPrincipal caller, UUID doctorId, @Valid DoctorDetails details) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        DoctorEntity doctor = find(doctorId, clinicId);
        doctor.update(details.name(), details.roomLabel());
        return doctor.toView();
    }

    @Transactional
    public DoctorView setActive(StaffPrincipal caller, UUID doctorId, boolean active) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        DoctorEntity doctor = find(doctorId, clinicId);
        doctor.setActive(active);
        return doctor.toView();
    }

    private DoctorEntity find(UUID doctorId, UUID clinicId) {
        return doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> new NotFoundException("Doctor"));
    }
}
