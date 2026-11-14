package com.baari.clinic;

/** SINGLE: one doctor serves the queue. POOL: any assigned doctor takes the next patient. */
public enum QueueMode {
    SINGLE,
    POOL
}
