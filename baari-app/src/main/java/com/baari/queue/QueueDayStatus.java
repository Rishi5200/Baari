package com.baari.queue;

/** Status of one queue on one service date: {@code OPEN → PAUSED ⇄ OPEN → CLOSED}. */
public enum QueueDayStatus {
    /** Patients can join and staff can call. */
    OPEN,
    /** No new joins; staff keep serving the patients already waiting. */
    PAUSED,
    /** Final for the date. Open tokens were expired when the day closed. */
    CLOSED
}
