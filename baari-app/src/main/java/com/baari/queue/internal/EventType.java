package com.baari.queue.internal;

/** Values of {@code token_event.type}. Token events name the change; day events start with DAY_. */
public enum EventType {
    JOINED,
    CALLED,
    CONSULT_STARTED,
    COMPLETED,
    NO_SHOW,
    RECALLED,
    CANCELLED,
    PRIORITY_CHANGED,
    EXPIRED,
    DAY_OPENED,
    DAY_PAUSED,
    DAY_RESUMED,
    DAY_CLOSED
}
