package com.baari.clinic.internal;

import com.baari.clinic.DoctorView;
import com.baari.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** A doctor of one clinic. Doctors are deactivated, never deleted: tokens will reference them. */
@Entity
@Table(name = "doctor")
public class DoctorEntity extends AssignedIdEntity {

    // Plain foreign-key column rather than a @ManyToOne: aggregates refer to each other by id.
    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "room_label")
    private String roomLabel;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected DoctorEntity() {}

    public DoctorEntity(UUID id, UUID clinicId, String name, String roomLabel) {
        super(id);
        this.clinicId = Objects.requireNonNull(clinicId, "clinicId");
        this.name = Objects.requireNonNull(name, "name");
        this.roomLabel = roomLabel;
        this.active = true;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public void update(String name, String roomLabel) {
        this.name = Objects.requireNonNull(name, "name");
        this.roomLabel = roomLabel;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public DoctorView toView() {
        return new DoctorView(getId(), clinicId, name, roomLabel, active);
    }
}
