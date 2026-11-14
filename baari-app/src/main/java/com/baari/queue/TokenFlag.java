package com.baari.queue;

/**
 * Marks on a token that record how it got to its state when that differs from the normal path
 * (PLAN §7 {@code token.flags}). The ETA training data uses them to filter out unusual visits,
 * for example consultations closed automatically at the end of the day.
 */
public enum TokenFlag {
    /** Staff called this token by hand instead of taking the policy's next one. */
    OVERRIDE_CALL,
    /** The patient went straight in from WAITING without being called first. */
    DIRECT_START,
    /** Staff marked a no-show before the grace period was over. */
    FORCED_NO_SHOW,
    /** Still in consultation when the day closed; completed at close time. */
    AUTO_CLOSED
}
