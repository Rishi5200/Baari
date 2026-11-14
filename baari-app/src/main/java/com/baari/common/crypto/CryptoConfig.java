package com.baari.common.crypto;

import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the cipher and the hasher from the configured keys; a bad key fails startup. */
@Configuration
@EnableConfigurationProperties(PrivacyProperties.class)
class CryptoConfig {

    @Bean
    FieldCipher fieldCipher(PrivacyProperties properties) {
        return new FieldCipher(decode("baari.privacy.data-key", properties.dataKey()));
    }

    @Bean
    PhoneHasher phoneHasher(PrivacyProperties properties) {
        return new PhoneHasher(decode("baari.privacy.phone-pepper", properties.phonePepper()));
    }

    private static byte[] decode(String name, String base64) {
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            // Name the property, but never echo its value.
            throw new IllegalArgumentException(name + " is not valid base64");
        }
    }
}
