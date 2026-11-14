package com.baari.clinic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Editable fields of a doctor. {@code roomLabel} is optional. */
public record DoctorDetails(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 40) String roomLabel) {}
