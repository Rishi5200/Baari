package com.baari.clinic;

/**
 * Result of creating a queue. {@code displayKey} is the TV display's secret: only its hash is
 * stored, so this is the one and only time the admin can see it (PLAN §9.1).
 */
public record CreatedQueue(ServiceQueueView queue, String displayKey) {

    @Override
    public String toString() {
        return "CreatedQueue[queue=" + queue + "]";
    }
}
