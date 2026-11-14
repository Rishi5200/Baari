package com.baari.common.crypto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Keys for protecting phone numbers (PLAN §14, {@code baari.privacy}). In production they come
 * from the env vars {@code BAARI_DATA_KEY} and {@code BAARI_PHONE_PEPPER}; without them the app
 * refuses to start rather than run with a guessable key.
 *
 * @param dataKey base64 of exactly 32 random bytes (AES-256 key)
 * @param phonePepper base64 of at least 32 random bytes (HMAC key)
 */
@Validated
@ConfigurationProperties("baari.privacy")
record PrivacyProperties(
        @NotBlank(message = "set BAARI_DATA_KEY (base64 of 32 random bytes)")
        String dataKey,

        @NotBlank(message = "set BAARI_PHONE_PEPPER (base64 of at least 32 random bytes)")
        String phonePepper) {

    /** Never print the keys. */
    @Override
    public String toString() {
        return "PrivacyProperties[dataKey=***, phonePepper=***]";
    }
}
