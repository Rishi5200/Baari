package com.baari.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class MaskingTest {

    @Test
    void showsOnlyTheLastFourDigits() {
        assertThat(Masking.phone("+919876543210")).isEqualTo("••••••3210");
        assertThat(Masking.phone("98765 43210")).isEqualTo("••••••3210");
    }

    @Test
    void acceptsAStoredLastFour() {
        assertThat(Masking.phone("3210")).isEqualTo("••••••3210");
    }

    @Test
    void showsNoDigitsWhenTooShort() {
        assertThat(Masking.phone("321")).isEqualTo("••••••");
        assertThat(Masking.phone("")).isEqualTo("••••••");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(Masking.phone(null)).isEmpty();
    }
}
