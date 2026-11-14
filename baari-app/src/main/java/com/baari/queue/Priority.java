package com.baari.queue;

/** How urgently a token should be called (PLAN §5). Stored as text in {@code token.priority}. */
public enum Priority {
    NORMAL,
    /** Elderly, pregnant, disabled: served ahead of NORMAL, but at most N in a row. */
    PRIORITY,
    /** Always next. */
    EMERGENCY
}
