package com.baari.queue.internal;

import com.baari.common.AssignedIdEntity;
import com.baari.common.crypto.EncryptedStringConverter;
import com.baari.queue.ActorType;
import com.baari.queue.GraceNotElapsedException;
import com.baari.queue.IllegalTransitionException;
import com.baari.queue.Priority;
import com.baari.queue.TokenFlag;
import com.baari.queue.TokenSource;
import com.baari.queue.TokenStatus;
import com.baari.queue.TokenView;
import com.baari.queue.VisitType;
import com.baari.queue.internal.TokenCommand.CallNext;
import com.baari.queue.internal.TokenCommand.CallSpecific;
import com.baari.queue.internal.TokenCommand.Cancel;
import com.baari.queue.internal.TokenCommand.CloseDay;
import com.baari.queue.internal.TokenCommand.Complete;
import com.baari.queue.internal.TokenCommand.MarkNoShow;
import com.baari.queue.internal.TokenCommand.Recall;
import com.baari.queue.internal.TokenCommand.SetPriority;
import com.baari.queue.internal.TokenCommand.Start;
import com.baari.queue.internal.Transition.Allowed;
import com.baari.queue.internal.Transition.Reason;
import com.baari.queue.internal.Transition.Rejected;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    private TokenEntity(UUID id) {
        super(id);
    }

    /** Patient details of a new token. The phone fields are all null or all set. */
    public record Patient(String name, String phoneE164, byte[] phoneHash, String phoneLast4) {
        public Patient {
            Objects.requireNonNull(name, "name");
            if ((phoneE164 == null) != (phoneHash == null) || (phoneE164 == null) != (phoneLast4 == null)) {
                throw new IllegalArgumentException("phone fields must be all set or all null");
            }
        }
    }

    /** A new WAITING token. The caller took {@code number} from the locked queue day. */
    public static TokenEntity issue(
            UUID id,
            UUID queueDayId,
            UUID clinicId,
            int number,
            String publicCode,
            Patient patient,
            VisitType visitType,
            Priority priority,
            TokenSource source,
            String consentVersion,
            UUID idempotencyKey,
            Instant joinedAt) {
        TokenEntity token = new TokenEntity(id);
        token.queueDayId = Objects.requireNonNull(queueDayId, "queueDayId");
        token.clinicId = Objects.requireNonNull(clinicId, "clinicId");
        token.number = number;
        token.sortKey = TokenView.initialSortKey(number);
        token.publicCode = Objects.requireNonNull(publicCode, "publicCode");
        token.patientName = patient.name();
        token.phone = patient.phoneE164();
        token.phoneHash =
                patient.phoneHash() == null ? null : patient.phoneHash().clone();
        token.phoneLast4 = patient.phoneLast4();
        token.visitType = Objects.requireNonNull(visitType, "visitType");
        token.priority = Objects.requireNonNull(priority, "priority");
        token.status = TokenStatus.WAITING;
        token.source = Objects.requireNonNull(source, "source");
        token.consentVersion = consentVersion;
        token.idempotencyKey = idempotencyKey;
        token.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        return token;
    }

    public UUID getQueueDayId() {
        return queueDayId;
    }

    public int getNumber() {
        return number;
    }

    public TokenStatus getStatus() {
        return status;
    }

    public String getPublicCode() {
        return publicCode;
    }

    public Priority getPriority() {
        return priority;
    }

    public VisitType getVisitType() {
        return visitType;
    }

    public TokenSource getSource() {
        return source;
    }

    /** The part of the token queue ordering needs (no patient details). */
    public TokenView toTokenView() {
        return new TokenView(getId(), number, priority, status, sortKey, joinedAt);
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

    // --- staff and patient actions (PLAN §4). Each asks the state machine first and throws a
    // domain exception if it says no; the caller has already locked the token's queue day. ---

    /**
     * Calls the token: by call-next ({@code override=false}) or by staff picking this patient out
     * of order ({@code override=true}, flagged OVERRIDE_CALL).
     */
    public void call(UUID doctorId, Instant at, boolean override) {
        Allowed allowed = require(override ? new CallSpecific() : new CallNext(), "called");
        calledAt = Objects.requireNonNull(at, "at");
        servedByDoctorId = doctorId;
        moveTo(allowed);
    }

    /**
     * Starts the consultation. From WAITING ("walked straight in") it is flagged DIRECT_START; the
     * doctor is then the one given here, since nobody called the patient.
     */
    public void start(UUID doctorIfNotCalled, Instant at) {
        Allowed allowed = require(new Start(), "started");
        consultStartedAt = Objects.requireNonNull(at, "at");
        if (servedByDoctorId == null) {
            servedByDoctorId = doctorIfNotCalled;
        }
        moveTo(allowed);
    }

    public void complete(Instant at) {
        Allowed allowed = require(new Complete(), "completed");
        completedAt = Objects.requireNonNull(at, "at");
        moveTo(allowed);
    }

    public void markNoShow(Instant at, Duration grace, boolean force) {
        // MarkNoShow needs a call time; a token that was never called has none, and the state
        // machine refuses no-show from anything but CALLED before it looks at the times.
        Instant called = calledAt != null ? calledAt : at;
        switch (TokenStateMachine.apply(status, new MarkNoShow(called, at, grace, force))) {
            case Allowed allowed -> moveTo(allowed);
            case Rejected r
            when r.reason() == Reason.GRACE_NOT_ELAPSED -> throw new GraceNotElapsedException((int) grace.toMinutes());
            case Rejected r -> throw refused("marked as no-show");
        }
    }

    /**
     * Puts a no-show back in line at {@code newSortKey} (from {@code NextTokenPolicy.recallSortKey}).
     * The earlier call is wiped, so {@code called_at} and the doctor describe the call that leads to
     * the consultation, which is what ETA training needs.
     */
    public void recall(LocalDate serviceDate, LocalDate today, long newSortKey) {
        switch (TokenStateMachine.apply(status, new Recall(serviceDate, today))) {
            case Allowed allowed -> {
                sortKey = newSortKey;
                calledAt = null;
                servedByDoctorId = null;
                moveTo(allowed);
            }
            case Rejected r
            when r.reason() == Reason.RECALL_OTHER_DAY ->
                throw new IllegalTransitionException("A no-show can only be recalled on the same day.");
            case Rejected r -> throw refused("recalled");
        }
    }

    public void cancel(ActorType by, Instant at) {
        Allowed allowed = require(new Cancel(by), "cancelled");
        cancelledAt = Objects.requireNonNull(at, "at");
        moveTo(allowed);
    }

    public void changePriority(Priority newPriority) {
        Allowed allowed = require(new SetPriority(), "given a new priority");
        priority = Objects.requireNonNull(newPriority, "newPriority");
        moveTo(allowed);
    }

    private Allowed require(TokenCommand command, String action) {
        return switch (TokenStateMachine.apply(status, command)) {
            case Allowed a -> a;
            case Rejected r -> throw refused(action);
        };
    }

    private IllegalTransitionException refused(String action) {
        return new IllegalTransitionException("This token is " + status + ", so it can't be " + action + ".");
    }

    public long getSortKey() {
        return sortKey;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public String getPatientName() {
        return patientName;
    }

    public String getPhoneLast4() {
        return phoneLast4;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public Instant getConsultStartedAt() {
        return consultStartedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getServedByDoctorId() {
        return servedByDoctorId;
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
