package com.baari.clinic;

import java.util.UUID;

/** Read model of a doctor. {@code roomLabel} may be null. */
public record DoctorView(UUID id, UUID clinicId, String name, String roomLabel, boolean active) {}
