package com.baari.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class PhoneNumberTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "9876543210",
                "98765 43210",
                "98765-43210",
                "(98765) 43210",
                "098765 43210",
                "91 98765 43210",
                "919876543210",
                "+91 98765 43210",
                "+91-98765-43210",
                "0091 98765 43210",
                " 9876543210 "
            })
    void indianFormsNormaliseToE164(String typed) {
        assertThat(PhoneNumber.parse(typed).e164()).isEqualTo("+919876543210");
    }

    @Test
    void foreignNumbersNeedAPlusOrDoubleZero() {
        assertThat(PhoneNumber.parse("+44 7911 123456").e164()).isEqualTo("+447911123456");
        assertThat(PhoneNumber.parse("0044 7911 123456").e164()).isEqualTo("+447911123456");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "12345",
                "abc",
                "98765 4321x",
                "1234567890", // Indian numbers don't start with 0–5 ...
                "5876543210",
                "+91 58765 43210",
                "+91 98765 4321", // ... and have exactly 10 digits
                "+91 98765 432109",
                "+0123456789",
                "+1234567890123456", // longer than E.164 allows
                "++919876543210"
            })
    void rejectsInvalidInput(String typed) {
        assertThatThrownBy(() -> PhoneNumber.parse(typed)).isInstanceOf(InvalidPhoneNumberException.class);
    }

    @Test
    void errorNeverRepeatsTheInput() {
        assertThatThrownBy(() -> PhoneNumber.parse("+91 58765 43210"))
                .isInstanceOf(InvalidPhoneNumberException.class)
                .hasMessageNotContaining("5876543210")
                .hasMessageNotContaining("3210");
    }

    @Test
    void lastFourDigits() {
        assertThat(PhoneNumber.parse("98765 43210").last4()).isEqualTo("3210");
    }

    @Test
    void toStringIsMasked() {
        assertThat(PhoneNumber.parse("98765 43210").toString())
                .isEqualTo("PhoneNumber[••••••3210]")
                .doesNotContain("98765");
    }
}
