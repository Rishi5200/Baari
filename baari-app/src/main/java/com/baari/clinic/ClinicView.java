package com.baari.clinic;

import java.time.ZoneId;
import java.util.UUID;

/** Read model of a clinic (tenant). */
public record ClinicView(UUID id, String slug, String name, ZoneId timezone) {}
