package com.baari.clinic.internal;

import com.baari.clinic.QrMode;
import com.baari.clinic.QueueMode;
import com.baari.clinic.ServiceQueueView;
import com.baari.common.AssignedIdEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A line patients join. The {@code settings} jsonb column is not mapped yet (database default
 * {@code '{}'}); it is first needed for queue limits in Phase 2.
 */
@Entity
@Table(name = "service_queue")
public class ServiceQueueEntity extends AssignedIdEntity {

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private QueueMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "qr_mode", nullable = false)
    private QrMode qrMode;

    @Column(name = "display_code_prefix", nullable = false)
    private String displayCodePrefix;

    @Column(name = "display_key_hash", nullable = false)
    private String displayKeyHash;

    @Column(name = "qr_secret", nullable = false)
    private byte[] qrSecret;

    // Doctor ids only, not DoctorEntity references: the join table is the whole relationship, and
    // the database foreign key still guarantees each id is a real doctor. A queue has a handful of
    // doctors at most, so loading them eagerly is cheap and keeps toView() usable after the
    // transaction ends (open-in-view is off).
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "queue_doctor", joinColumns = @JoinColumn(name = "queue_id"))
    @Column(name = "doctor_id", nullable = false)
    private Set<UUID> doctorIds = new HashSet<>();

    protected ServiceQueueEntity() {}

    public ServiceQueueEntity(
            UUID id,
            UUID clinicId,
            String slug,
            String name,
            QueueMode mode,
            QrMode qrMode,
            String displayCodePrefix,
            String displayKeyHash,
            byte[] qrSecret) {
        super(id);
        this.clinicId = Objects.requireNonNull(clinicId, "clinicId");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.name = Objects.requireNonNull(name, "name");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.qrMode = Objects.requireNonNull(qrMode, "qrMode");
        this.displayCodePrefix = Objects.requireNonNull(displayCodePrefix, "displayCodePrefix");
        this.displayKeyHash = Objects.requireNonNull(displayKeyHash, "displayKeyHash");
        this.qrSecret = Objects.requireNonNull(qrSecret, "qrSecret").clone();
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public String getSlug() {
        return slug;
    }

    public String getDisplayKeyHash() {
        return displayKeyHash;
    }

    public byte[] getQrSecret() {
        return qrSecret.clone();
    }

    public Set<UUID> getDoctorIds() {
        return Set.copyOf(doctorIds);
    }

    public void update(String name, QueueMode mode, QrMode qrMode, String displayCodePrefix) {
        this.name = Objects.requireNonNull(name, "name");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.qrMode = Objects.requireNonNull(qrMode, "qrMode");
        this.displayCodePrefix = Objects.requireNonNull(displayCodePrefix, "displayCodePrefix");
    }

    public void assignDoctors(Set<UUID> doctorIds) {
        this.doctorIds.clear();
        this.doctorIds.addAll(doctorIds);
    }

    public ServiceQueueView toView() {
        return new ServiceQueueView(getId(), clinicId, slug, name, mode, qrMode, displayCodePrefix, doctorIds);
    }
}
