package com.baari.clinic;

import com.baari.clinic.internal.DoctorRepository;
import com.baari.clinic.internal.ServiceQueueEntity;
import com.baari.clinic.internal.ServiceQueueRepository;
import com.baari.common.DuplicateException;
import com.baari.common.Ids;
import com.baari.common.InvalidRequestException;
import com.baari.common.NotFoundException;
import com.baari.common.Secrets;
import com.baari.common.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Service queues of the caller's clinic. Writes need CLINIC_ADMIN; reads are open to clinic staff. */
@Service
@Validated
public class QueueAdminService {

    /** 32 random bytes = 256 bits, for both the TV display key and the QR HMAC key. */
    private static final int SECRET_BYTES = 32;

    private final ServiceQueueRepository queues;
    private final DoctorRepository doctors;

    QueueAdminService(ServiceQueueRepository queues, DoctorRepository doctors) {
        this.queues = queues;
        this.doctors = doctors;
    }

    @Transactional(readOnly = true)
    public List<ServiceQueueView> list(StaffPrincipal caller) {
        UUID clinicId = Permissions.requireClinicMember(caller);
        return queues.findAllByClinicIdOrderBySlug(clinicId).stream()
                .map(ServiceQueueEntity::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceQueueView get(StaffPrincipal caller, UUID queueId) {
        UUID clinicId = Permissions.requireClinicMember(caller);
        return find(queueId, clinicId).toView();
    }

    @Transactional
    public CreatedQueue create(
            StaffPrincipal caller,
            @NotBlank @Size(max = 60) @Pattern(regexp = ValidationPatterns.SLUG) String slug,
            @Valid QueueDetails details) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        checkDoctors(clinicId, details);
        if (queues.existsByClinicIdAndSlug(clinicId, slug)) {
            throw new DuplicateException("A queue with this slug already exists in your clinic.");
        }
        String displayKey = Secrets.randomToken(SECRET_BYTES);
        ServiceQueueEntity queue = new ServiceQueueEntity(
                Ids.newId(),
                clinicId,
                slug,
                details.name(),
                details.mode(),
                details.qrMode(),
                details.displayCodePrefix(),
                Secrets.sha256Hex(displayKey),
                Secrets.randomBytes(SECRET_BYTES));
        queue.assignDoctors(details.doctorIds());
        return new CreatedQueue(queues.save(queue).toView(), displayKey);
    }

    @Transactional
    public ServiceQueueView update(StaffPrincipal caller, UUID queueId, @Valid QueueDetails details) {
        UUID clinicId = Permissions.requireClinicAdmin(caller);
        ServiceQueueEntity queue = find(queueId, clinicId);
        checkDoctors(clinicId, details);
        queue.update(details.name(), details.mode(), details.qrMode(), details.displayCodePrefix());
        queue.assignDoctors(details.doctorIds());
        return queue.toView();
    }

    /**
     * Every doctor must belong to the caller's clinic. A foreign doctor id is reported as "not
     * found", like any other cross-tenant id. A SINGLE queue is served by one doctor, so it may
     * have at most one assigned (zero is allowed while the clinic is still being set up).
     */
    private void checkDoctors(UUID clinicId, QueueDetails details) {
        if (details.mode() == QueueMode.SINGLE && details.doctorIds().size() > 1) {
            throw new InvalidRequestException("A SINGLE queue can have at most one doctor.");
        }
        if (!details.doctorIds().isEmpty()
                && doctors.countByClinicIdAndIdIn(clinicId, details.doctorIds())
                        != details.doctorIds().size()) {
            throw new NotFoundException("Doctor");
        }
    }

    private ServiceQueueEntity find(UUID queueId, UUID clinicId) {
        return queues.findByIdAndClinicId(queueId, clinicId).orElseThrow(() -> new NotFoundException("Queue"));
    }
}
