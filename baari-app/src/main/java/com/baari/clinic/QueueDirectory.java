package com.baari.clinic;

import com.baari.clinic.internal.ClinicRepository;
import com.baari.clinic.internal.DoctorEntity;
import com.baari.clinic.internal.DoctorRepository;
import com.baari.clinic.internal.ServiceQueueRepository;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queue lookup for other modules (the queue module needs a queue's doctors and its
 * clinic's time zone). It does no permission check: patients joining by QR have no principal, and
 * staff callers are checked by the queue module against the returned clinic id.
 */
@Service
public class QueueDirectory {

    private final ServiceQueueRepository queues;
    private final ClinicRepository clinics;
    private final DoctorRepository doctors;

    QueueDirectory(ServiceQueueRepository queues, ClinicRepository clinics, DoctorRepository doctors) {
        this.queues = queues;
        this.clinics = clinics;
        this.doctors = doctors;
    }

    @Transactional(readOnly = true)
    public Optional<QueueInfo> find(UUID queueId) {
        return queues.findById(queueId)
                .flatMap(queue -> clinics.findById(queue.getClinicId())
                        .map(clinic ->
                                new QueueInfo(queue.toView(), clinic.toView().timezone())));
    }

    /** The queue behind a join link {@code /j/{clinicSlug}/{queueSlug}}, with its active doctors. */
    @Transactional(readOnly = true)
    public Optional<PublicQueue> findBySlugs(String clinicSlug, String queueSlug) {
        return clinics.findBySlug(clinicSlug)
                .flatMap(clinic -> queues.findByClinicIdAndSlug(clinic.getId(), queueSlug)
                        .map(queue -> new PublicQueue(
                                new QueueInfo(queue.toView(), clinic.toView().timezone()),
                                clinic.toView().name(),
                                doctors.findAllById(queue.getDoctorIds()).stream()
                                        .map(DoctorEntity::toView)
                                        .filter(DoctorView::active)
                                        .sorted(Comparator.comparing(DoctorView::name))
                                        .toList())));
    }
}
