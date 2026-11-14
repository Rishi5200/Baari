package com.baari.clinic;

import java.time.ZoneId;
import java.util.Objects;

/**
 * A queue together with its clinic's time zone, which decides the queue's service date. For the
 * queue module; no caller check is done to build it, so callers do their own access check.
 */
public record QueueInfo(ServiceQueueView queue, ZoneId timezone) {

    public QueueInfo {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(timezone, "timezone");
    }
}
