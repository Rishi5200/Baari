package com.baari.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.baari.common.Secrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PhoneHasherTest {

    private final byte[] pepper = Secrets.randomBytes(32);
    private final PhoneHasher hasher = new PhoneHasher(pepper);

    @Test
    void sameNumberAlwaysHashesTheSame() {
        byte[] first = hasher.hash(PhoneNumber.parse("98765 43210"));
        byte[] again = new PhoneHasher(pepper.clone()).hash(PhoneNumber.parse("+91-98765-43210"));

        assertThat(first).hasSize(32).isEqualTo(again);
    }

    @Test
    void differentNumbersHashDifferently() {
        assertThat(hasher.hash(PhoneNumber.parse("9876543210")))
                .isNotEqualTo(hasher.hash(PhoneNumber.parse("9876543211")));
    }

    @Test
    void differentPepperHashesDifferently() {
        PhoneNumber phone = PhoneNumber.parse("9876543210");

        assertThat(new PhoneHasher(Secrets.randomBytes(32)).hash(phone)).isNotEqualTo(hasher.hash(phone));
    }

    @Test
    void pepperMustBeAtLeast32Bytes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PhoneHasher(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PhoneHasher(new byte[31]));
        assertThat(new PhoneHasher(new byte[64])).isNotNull();
    }
}
