package com.baari.queue;

import java.time.Instant;

/**
 * The rotating join link a waiting-room TV shows (PLAN §9.3).
 *
 * @param path {@code /j/{clinic}/{queue}?t=<window>&sig=<signature>}; the page adds its own host
 * @param validUntil after this the link is refused, so the TV must have fetched a new one
 */
public record DisplayJoinLink(String path, long window, Instant validUntil) {}
