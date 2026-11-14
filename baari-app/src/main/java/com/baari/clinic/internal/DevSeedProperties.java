package com.baari.clinic.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Logins and the TV display key for the dev-profile demo clinic. Values live in
 * {@code application-dev.yml}, which only the dev profile loads.
 *
 * @param displayKey fixed TV display key for both demo queues, so the TV page can be opened
 *     locally without copying a random key from somewhere (only its hash is stored, as in prod)
 */
@Validated
@ConfigurationProperties("baari.dev-seed")
record DevSeedProperties(
        @NotNull @Valid Login platformAdmin,
        @NotNull @Valid Login clinicAdmin,
        @NotNull @Valid Login doctor,
        @NotNull @Valid Login receptionist,
        @NotBlank String displayKey) {

    record Login(@NotBlank String username, @NotBlank String password) {

        @Override
        public String toString() {
            return "Login[username=" + username + "]";
        }
    }

    @Override
    public String toString() {
        return "DevSeedProperties[platformAdmin=" + platformAdmin + ", clinicAdmin=" + clinicAdmin + ", doctor="
                + doctor + ", receptionist=" + receptionist + "]";
    }
}
