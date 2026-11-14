package com.baari.clinic.internal;

import com.baari.clinic.ClinicView;
import com.baari.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * A clinic (tenant). The {@code settings} jsonb column is not mapped yet: nothing reads it until
 * later phases, and the database default ({@code '{}'}) fills it on insert.
 */
@Entity
@Table(name = "clinic")
public class ClinicEntity extends AssignedIdEntity {

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClinicEntity() {}

    public ClinicEntity(UUID id, String slug, String name, ZoneId timezone, Instant createdAt) {
        super(id);
        this.slug = Objects.requireNonNull(slug, "slug");
        this.name = Objects.requireNonNull(name, "name");
        this.timezone = Objects.requireNonNull(timezone, "timezone").getId();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String getSlug() {
        return slug;
    }

    public ClinicView toView() {
        return new ClinicView(getId(), slug, name, ZoneId.of(timezone));
    }
}
