package com.baari.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.baari.common.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FieldCipherTest {

    private final FieldCipher cipher = new FieldCipher(Secrets.randomBytes(32));

    @Test
    void roundTrips() {
        assertThat(cipher.decrypt(cipher.encrypt("+919876543210"))).isEqualTo("+919876543210");
        assertThat(cipher.decrypt(cipher.encrypt("राजेश"))).isEqualTo("राजेश");
        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
    }

    @Test
    void sameValueEncryptsDifferentlyEachTime() {
        byte[] first = cipher.encrypt("+919876543210");
        byte[] second = cipher.encrypt("+919876543210");

        assertThat(first).isNotEqualTo(second);
        // The IV (bytes 1..12) is what differs.
        assertThat(Arrays.copyOfRange(first, 1, 13)).isNotEqualTo(Arrays.copyOfRange(second, 1, 13));
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        byte[] stored = cipher.encrypt("+919876543210");

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("9876543210");
        assertThat(stored).hasSize(1 + 12 + "+919876543210".length() + 16);
    }

    @Test
    void tamperedValueIsRejected() {
        byte[] stored = cipher.encrypt("+919876543210");
        stored[stored.length - 1] ^= 1;

        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(stored));
    }

    @Test
    void anotherKeyCannotDecrypt() {
        byte[] stored = cipher.encrypt("+919876543210");
        FieldCipher other = new FieldCipher(Secrets.randomBytes(32));

        assertThatIllegalStateException()
                .isThrownBy(() -> other.decrypt(stored))
                .withMessageNotContaining("9876543210");
    }

    @Test
    void malformedValuesAreRejected() {
        byte[] stored = cipher.encrypt("x");
        byte[] wrongVersion = stored.clone();
        wrongVersion[0] = 2;

        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(null));
        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(new byte[28]));
        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(wrongVersion));
    }

    @Test
    void keyMustBe32Bytes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FieldCipher(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new FieldCipher(new byte[16]));
        assertThatIllegalArgumentException().isThrownBy(() -> new FieldCipher(new byte[33]));
    }
}
