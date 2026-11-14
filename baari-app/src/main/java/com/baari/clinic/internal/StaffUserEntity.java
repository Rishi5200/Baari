package com.baari.clinic.internal;

import com.baari.clinic.StaffRole;
import com.baari.clinic.StaffUserView;
import com.baari.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A staff login. Invariants checked here because the table alone can't express them:
 * PLATFORM_ADMIN has no clinic and every other role has one; a DOCTOR is linked to a doctor record
 * (needed to restrict them to their own queues, PLAN §9.2) and no other role is.
 */
@Entity
@Table(name = "staff_user")
public class StaffUserEntity extends AssignedIdEntity {

    @Column(name = "clinic_id", updatable = false)
    private UUID clinicId;

    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private StaffRole role;

    @Column(name = "doctor_id")
    private UUID doctorId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StaffUserEntity() {}

    public StaffUserEntity(
            UUID id,
            UUID clinicId,
            String username,
            String passwordHash,
            StaffRole role,
            UUID doctorId,
            Instant createdAt) {
        super(id);
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        checkClinic(role, clinicId);
        checkDoctor(role, doctorId);
        this.clinicId = clinicId;
        this.doctorId = doctorId;
        this.enabled = true;
    }

    private static void checkClinic(StaffRole role, UUID clinicId) {
        if (role.belongsToClinic() && clinicId == null) {
            throw new IllegalArgumentException(role + " must belong to a clinic");
        }
        if (!role.belongsToClinic() && clinicId != null) {
            throw new IllegalArgumentException("PLATFORM_ADMIN must not belong to a clinic");
        }
    }

    private static void checkDoctor(StaffRole role, UUID doctorId) {
        if (role == StaffRole.DOCTOR && doctorId == null) {
            throw new IllegalArgumentException("DOCTOR must be linked to a doctor");
        }
        if (role != StaffRole.DOCTOR && doctorId != null) {
            throw new IllegalArgumentException("only DOCTOR can be linked to a doctor");
        }
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public StaffRole getRole() {
        return role;
    }

    public UUID getDoctorId() {
        return doctorId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void changeRole(StaffRole role, UUID doctorId) {
        Objects.requireNonNull(role, "role");
        checkClinic(role, clinicId);
        checkDoctor(role, doctorId);
        this.role = role;
        this.doctorId = doctorId;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public StaffUserView toView() {
        return new StaffUserView(getId(), clinicId, username, role, doctorId, enabled);
    }
}
