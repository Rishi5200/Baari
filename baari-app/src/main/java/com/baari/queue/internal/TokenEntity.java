package com.baari.queue.internal;

import com.baari.common.AssignedIdEntity;
import com.baari.common.crypto.EncryptedStringConverter;
import com.baari.queue.Priority;
import com.baari.queue.TokenFlag;
import com.baari.queue.TokenSource;
import com.baari.queue.TokenStatus;
import com.baari.queue.VisitType;
import com.baari.queue.internal.TokenCommand.CloseDay;
import com.baari.queue.internal.Transition.Allowed;
import com.baari.queue.internal.Transition.Rejected;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A patient's place in a queue day. Tokens are inserted with SQL on the issuance hot path (PLAN
 * §6.1); this entity is for reading them and for status changes, which always go through
 * {@link TokenStateMachine}.
 *
 * <p>{@code @Version} turns a concurrent double-click on the same token into an optimistic lock
 * failure (HTTP 409) instead of two transitions (PLAN §6.3).
 */
@Entity
@Table(name = "token")
public class TokenEntity extends AssignedIdEntity {

    @Column(name = "queue_day_id", nullable = false, updatable = false)
    private UUID queueDayId;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    @Column(name = "sort_key", nullable = false)
    private long sortKey;

    @Column(name = "public_code", nullable = false, updatable = false)
    private String publicCode;

    @Column(name = "patient_name")
    private String patientName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "phone_enc")
    private String phone;

    @Column(name = "phone_hash")
    private byte[] phoneHash;

    @Column(name = "phone_last4")
    private String phoneLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_type", nullable = false)
    private VisitType visitType;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TokenStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private TokenSource source;

    @Column(name = "consent_version", updatable = false)
    private String consentVersion;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "consult_started_at")
    private Instant consultStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "served_by_doctor_id")
    private UUID servedByDoctorId;

    // TokenFlag names. A list rather than an enum set because Postgres stores text[]; the list is
    // replaced, never changed in place, so Hibernate's dirty check always sees the change.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "flags", nullable = false, columnDefinition = "text[]")
    private List<String> flags = new ArrayList<>();

    @Column(name = "pii_purged_at")
    private Instant piiPurgedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected TokenEntity() {}

    public UUID getQueueDayId() {
        return queueDayId;
    }

    public int getNumber() {
        return number;
    }

    public TokenStatus getStatus() {
        return status;
    }

    public List<String> getFlags() {
        return List.copyOf(flags);
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Applies day close (PLAN §4): open tokens expire; a consultation still running is completed at
     * close time and flagged {@code AUTO_CLOSED}, so its made-up duration can be left out of ETA
     * training. Only called for tokens in an open status.
     *
     * @return the status before the change
     */
    public TokenStatus closeForDay(Instant at) {
        Objects.requireNonNull(at, "at");
        TokenStatus before = status;
        Allowed allowed = allowed(new CloseDay());
        if (allowed.to() == TokenStatus.COMPLETED) {
            completedAt = at;
        }
        moveTo(allowed);
        return before;
    }

    private Allowed allowed(TokenCommand command) {
        return switch (TokenStateMachine.apply(status, command)) {
            case Allowed a -> a;
            case Rejected r ->
                // Callers only pass tokens the command applies to; anything else is a bug here.
                throw new IllegalStateException(
                        "token " + getId() + ": " + command + " from " + status + " rejected: " + r.reason());
        };
    }

    private void moveTo(Allowed allowed) {
        status = allowed.to();
        allowed.flag().map(TokenFlag::name).ifPresent(this::addFlag);
    }

    private void addFlag(String flag) {
        if (!flags.contains(flag)) {
            List<String> updated = new ArrayList<>(flags);
            updated.add(flag);
            flags = updated;
        }
    }
}
