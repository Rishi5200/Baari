package com.baari.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class MutableClockTest {

    private static final Instant START = Instant.parse("2026-01-05T04:00:00Z");

    @Test
    void staysStillUntilMoved() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.instant()).isEqualTo(START);
        assertThat(clock.instant()).isEqualTo(START);
    }

    @Test
    void advanceMovesTimeForward() {
        MutableClock clock = MutableClock.at(START);

        clock.advance(Duration.ofMinutes(5));

        assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void setJumpsToAnyInstant() {
        MutableClock clock = MutableClock.at(START);
        Instant earlier = START.minus(Duration.ofDays(1));

        clock.set(earlier);

        assertThat(clock.instant()).isEqualTo(earlier);
    }

    @Test
    void withZoneKeepsCurrentInstantButChangesZone() {
        MutableClock clock = MutableClock.at(START);

        var utc = clock.withZone(ZoneId.of("UTC"));

        assertThat(utc.getZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(utc.instant()).isEqualTo(START);
    }
}
