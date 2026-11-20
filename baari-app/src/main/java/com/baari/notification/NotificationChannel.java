package com.baari.notification;

import java.util.UUID;

/**
 * A way to reach a patient (PLAN §11.4). The planner creates one notification per channel that can
 * reach the token; the dispatcher calls {@link #send} and retries when it throws.
 */
interface NotificationChannel {

    /** The value stored in {@code notification.channel}, for example {@code LOG} or {@code TELEGRAM}. */
    String name();

    /** Whether this channel can reach the patient behind the token right now. */
    boolean canReach(UUID tokenId);

    /** Delivers the message. Throws if it could not be delivered, so the dispatcher retries. */
    void send(UUID tokenId, String text);
}
