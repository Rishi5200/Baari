package com.baari.queue.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Small helpers for {@code JdbcClient} parameters. */
final class Sql {

    private Sql() {}

    /** The Postgres driver binds {@code OffsetDateTime} to {@code timestamptz}; it has no Instant support. */
    static OffsetDateTime timestamptz(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
