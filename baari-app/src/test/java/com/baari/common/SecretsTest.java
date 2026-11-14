package com.baari.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SecretsTest {

    @Test
    void randomTokenIsUrlSafeAndDifferentEachTime() {
        String a = Secrets.randomToken(32);
        String b = Secrets.randomToken(32);

        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+"); // 32 bytes → 43 base64url chars
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void randomBytesHaveRequestedLength() {
        assertThat(Secrets.randomBytes(32)).hasSize(32);
    }

    @Test
    void sha256HexMatchesKnownVector() {
        assertThat(Secrets.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
