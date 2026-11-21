package com.baari.clinic;

import com.baari.clinic.internal.ClinicEntity;
import com.baari.clinic.internal.ClinicRepository;
import com.baari.clinic.internal.DoctorEntity;
import com.baari.clinic.internal.DoctorRepository;
import com.baari.clinic.internal.ServiceQueueEntity;
import com.baari.clinic.internal.ServiceQueueRepository;
import com.baari.common.Secrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /** Every clinic, for scheduled jobs that run per clinic (nightly model training). */
    @Transactional(readOnly = true)
    public java.util.List<ClinicView> allClinics() {
        return clinics.findAll().stream()
                .map(ClinicEntity::toView)
                .sorted(Comparator.comparing(ClinicView::slug))
                .toList();
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
                        .map(queue -> publicQueue(clinic, queue)));
    }

    /** Same as {@link #findBySlugs} but from the queue id (the staff poster page knows only that). */
    @Transactional(readOnly = true)
    public Optional<PublicQueue> findPublic(UUID queueId) {
        return queues.findById(queueId)
                .flatMap(queue -> clinics.findById(queue.getClinicId()).map(clinic -> publicQueue(clinic, queue)));
    }

    /**
     * Checks the TV display key. The key is 256 random bits and only its SHA-256 is stored, so the
     * comparison is done on hashes in constant time. An unknown queue answers like a wrong key.
     */
    @Transactional(readOnly = true)
    public boolean displayKeyMatches(UUID queueId, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        byte[] given = Secrets.sha256Hex(key).getBytes(StandardCharsets.UTF_8);
        return queues.findById(queueId)
                .map(queue ->
                        MessageDigest.isEqual(given, queue.getDisplayKeyHash().getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }

    /** Room label of each of the queue's doctors that has one (doctor id → label), for "go to Room 2". */
    @Transactional(readOnly = true)
    public Map<UUID, String> roomLabels(UUID queueId) {
        return queues.findById(queueId)
                .map(queue -> doctors.findAllById(queue.getDoctorIds()).stream()
                        .map(DoctorEntity::toView)
                        .filter(d -> d.roomLabel() != null && !d.roomLabel().isBlank())
                        .collect(Collectors.toMap(DoctorView::id, DoctorView::roomLabel)))
                .orElse(Map.of());
    }

    private PublicQueue publicQueue(ClinicEntity clinic, ServiceQueueEntity queue) {
        return new PublicQueue(
                new QueueInfo(queue.toView(), clinic.toView().timezone()),
                clinic.toView().slug(),
                clinic.toView().name(),
                doctors.findAllById(queue.getDoctorIds()).stream()
                        .map(DoctorEntity::toView)
                        .filter(DoctorView::active)
                        .sorted(Comparator.comparing(DoctorView::name))
                        .toList());
    }
}
