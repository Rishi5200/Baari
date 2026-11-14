package com.baari.web;

import com.baari.clinic.QrMode;
import com.baari.clinic.QueueDetails;
import com.baari.clinic.QueueMode;
import com.baari.clinic.ServiceQueueView;
import com.baari.common.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The queue form as the browser sends it. Differs from {@link QueueDetails} in two ways: it carries
 * the slug (used on create only), and unticked doctor checkboxes arrive as a missing field, which
 * we turn into an empty set.
 *
 * <p>Public only because templates call its methods through reflection (SpEL needs public types).
 */
public record QueueForm(
        @Size(max = 60) @Pattern(regexp = ValidationPatterns.SLUG)
        String slug,

        @NotBlank @Size(max = 120) String name,
        @NotNull QueueMode mode,
        @NotNull QrMode qrMode,
        @NotNull @Pattern(regexp = "^[A-Z]{1,3}$") String displayCodePrefix,
        List<UUID> doctorIds) {

    static QueueForm empty() {
        return new QueueForm("", "", QueueMode.POOL, QrMode.STATIC, "A", List.of());
    }

    static QueueForm of(ServiceQueueView queue) {
        return new QueueForm(
                queue.slug(),
                queue.name(),
                queue.mode(),
                queue.qrMode(),
                queue.displayCodePrefix(),
                List.copyOf(queue.doctorIds()));
    }

    QueueDetails toDetails() {
        return new QueueDetails(
                name, mode, qrMode, displayCodePrefix, doctorIds == null ? Set.of() : Set.copyOf(doctorIds));
    }

    public boolean hasDoctor(UUID doctorId) {
        return doctorIds != null && doctorIds.contains(doctorId);
    }
}
