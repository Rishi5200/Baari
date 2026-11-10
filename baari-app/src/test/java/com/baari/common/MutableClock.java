package com.baari.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A Clock that tests move by hand. Time is the core input of this product; tests control it. */
public final class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public static MutableClock at(Instant start) {
        return new MutableClock(start, ZoneId.of("Asia/Kolkata"));
    }

    public synchronized void advance(Duration amount) {
        now = now.plus(amount);
    }

    public synchronized void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant(), newZone);
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }
}
