package com.baari.clinic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * Editable fields of a queue. The slug is not here: it is set once at creation because it is
 * printed into QR posters, and changing it would break them.
 *
 * @param displayCodePrefix letters before the token number, e.g. "A" in "A-017"
 */
public record QueueDetails(
        @NotBlank @Size(max = 120) String name,
        @NotNull QueueMode mode,
        @NotNull QrMode qrMode,
        @NotNull @Pattern(regexp = "^[A-Z]{1,3}$") String displayCodePrefix,
        @NotNull Set<@NotNull UUID> doctorIds) {

    public QueueDetails {
        doctorIds = doctorIds == null ? null : Set.copyOf(doctorIds);
    }
}
