package com.baari.queue;

import java.util.List;
import java.util.UUID;

/**
 * What the join page shows before a patient joins (PLAN Appendix A). Typical wait arrives with
 * the ETA work in Phase 4.
 *
 * @param status today's status, or null if the queue hasn't been opened today
 * @param doctors names of the queue's active doctors
 */
public record PublicQueueView(
        UUID queueId, String clinicName, String queueName, List<String> doctors, QueueDayStatus status, int waiting) {

    public PublicQueueView {
        doctors = List.copyOf(doctors);
    }
}
