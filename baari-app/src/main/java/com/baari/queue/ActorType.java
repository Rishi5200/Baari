package com.baari.queue;

/** Who caused a token change. Matches {@code token_event.actor_type} (PLAN §7). */
public enum ActorType {
    PATIENT,
    STAFF,
    SYSTEM
}
