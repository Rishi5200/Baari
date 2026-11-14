package com.baari.queue;

import java.util.UUID;

/**
 * "Something about this queue changed", published as a Spring application event from inside the
 * transaction that made the change. Live-update code listens after commit (never before, or a
 * reader could miss the change) and pushes fresh snapshots. It carries no data about the change on
 * purpose: snapshots are always rebuilt from the database.
 *
 * <p>This is not a domain event for other systems (those go through the outbox); it only lets the
 * {@code realtime} module learn about changes without {@code queue} depending on it.
 */
public record QueueChanged(UUID queueId, UUID queueDayId, long seq) {}
