package com.baari.common;

import java.util.UUID;

/**
 * Id generation in one place. Ids are created in the application, not the database.
 *
 * <p>Random UUIDs for now. UUIDv7 would be time-ordered, but it needs the current time, and the
 * only legal source of time here is an injected Clock. We can revisit if index locality matters.
 */
public final class Ids {

    private Ids() {}

    public static UUID newId() {
        return UUID.randomUUID();
    }
}
