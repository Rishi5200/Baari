package com.baari.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Startup behaviour with good, missing and broken keys. No database needed. */
@Tag("unit")
class CryptoConfigTest {

    private static final String KEY_32 = Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(CryptoConfig.class))
            .withBean(EncryptedStringConverter.class);

    @Test
    void validKeysGiveWorkingBeans() {
        runner.withPropertyValues("baari.privacy.data-key=" + KEY_32, "baari.privacy.phone-pepper=" + KEY_32)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EncryptedStringConverter converter = context.getBean(EncryptedStringConverter.class);
                    byte[] stored = converter.convertToDatabaseColumn("+919876543210");
                    assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("+919876543210");
                    assertThat(converter.convertToDatabaseColumn(null)).isNull();
                    assertThat(converter.convertToEntityAttribute(null)).isNull();
                    assertThat(context.getBean(PhoneHasher.class)).isNotNull();
                });
    }

    @Test
    void missingKeysStopStartupAndSayWhichEnvVar() {
        runner.run(context -> assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessageContaining("BAARI_DATA_KEY")
                .hasMessageContaining("BAARI_PHONE_PEPPER"));
    }

    @Test
    void brokenKeyStopsStartupWithoutEchoingIt() {
        runner.withPropertyValues("baari.privacy.data-key=not*base64!", "baari.privacy.phone-pepper=" + KEY_32)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("baari.privacy.data-key is not valid base64")
                        .hasMessageNotContaining("not*base64!"));
    }

    @Test
    void shortKeyStopsStartup() {
        String short16 = Base64.getEncoder().encodeToString(new byte[16]);
        runner.withPropertyValues("baari.privacy.data-key=" + short16, "baari.privacy.phone-pepper=" + KEY_32)
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("exactly 32 bytes"));
    }

    @Test
    void propertiesNeverPrintTheKeys() {
        assertThat(new PrivacyProperties(KEY_32, KEY_32).toString()).doesNotContain(KEY_32);
    }
}
