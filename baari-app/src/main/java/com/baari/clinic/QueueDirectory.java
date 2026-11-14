package com.baari.clinic;

import com.baari.clinic.internal.ClinicRepository;
import com.baari.clinic.internal.ServiceQueueRepository;
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

    QueueDirectory(ServiceQueueRepository queues, ClinicRepository clinics) {
        this.queues = queues;
        this.clinics = clinics;
    }

    @Transactional(readOnly = true)
    public Optional<QueueInfo> find(UUID queueId) {
        return queues.findById(queueId)
                .flatMap(queue -> clinics.findById(queue.getClinicId())
                        .map(clinic ->
                                new QueueInfo(queue.toView(), clinic.toView().timezone())));
    }
}
