package com.baari.notification;

/** What a notification says (PLAN §11.4). Stored as text in {@code notification.kind}. */
enum NotificationKind {
    /** A few patients are ahead: time to come back to the clinic. */
    NEAR_TURN,
    /** The patient has been called. */
    CALLED,
    /** The day closed while the patient was still open. */
    QUEUE_CLOSED
}
