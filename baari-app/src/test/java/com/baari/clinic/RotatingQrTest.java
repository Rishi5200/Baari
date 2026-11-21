package com.baari.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class RotatingQrTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();
    private static final UUID QUEUE = new UUID(1, 2);

    @Test
    void windowsAreWholeMinutes() {
        assertThat(RotatingQr.window(Instant.ofEpochSecond(119))).isEqualTo(1);
        assertThat(RotatingQr.window(Instant.ofEpochSecond(120))).isEqualTo(2);
        assertThat(RotatingQr.validUntil(2)).isEqualTo(Instant.ofEpochSecond(240));
    }

    @Test
    void theSignatureDependsOnQueueWindowAndSecret() {
        String sig = RotatingQr.sign(SECRET, QUEUE, 100);
        assertThat(sig).hasSize(22).matches("[A-Za-z0-9_-]+").isEqualTo(RotatingQr.sign(SECRET, QUEUE, 100));
        assertThat(RotatingQr.sign(SECRET, QUEUE, 101)).isNotEqualTo(sig);
        assertThat(RotatingQr.sign(SECRET, new UUID(1, 3), 100)).isNotEqualTo(sig);
        assertThat(RotatingQr.sign("another-secret-another-secret!!!".getBytes(), QUEUE, 100))
                .isNotEqualTo(sig);
    }
}
