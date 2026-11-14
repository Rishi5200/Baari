package com.baari.clinic;

import java.util.Set;
import java.util.UUID;

/** Read model of a service queue. Secrets (display key hash, QR secret) are deliberately left out. */
public record ServiceQueueView(
        UUID id,
        UUID clinicId,
        String slug,
        String name,
        QueueMode mode,
        QrMode qrMode,
        String displayCodePrefix,
        Set<UUID> doctorIds) {

    public ServiceQueueView {
        doctorIds = Set.copyOf(doctorIds);
    }
}
