package com.baari.queue.internal;

import com.baari.common.AssignedIdEntity;
import com.baari.queue.QueueDayStatus;
import com.baari.queue.QueueDayView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One queue on one service date. Rows are created by {@link QueueDaySql#insertIfAbsent} (an
 * {@code INSERT ... ON CONFLICT DO NOTHING}), so this class has no public constructor.
 *
 * <p>No {@code @Version}: every change to a day happens while holding its row lock (PLAN §6), so
 * optimistic locking would add nothing. Callers must load it with
 * {@link QueueDayRepository#lockByQueueIdAndServiceDate} before calling the mutators.
 */
@Entity
@Table(name = "queue_day")
public class QueueDayEntity extends AssignedIdEntity {

    @Column(name = "queue_id", nullable = false, updatable = false)
    private UUID queueId;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "service_date", nullable = false, updatable = false)
    private LocalDate serviceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private QueueDayStatus status;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    @Column(name = "last_event_seq", nullable = false)
    private long lastEventSeq;

    @Column(name = "consecutive_priority_served", nullable = false)
    private int consecutivePriorityServed;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected QueueDayEntity() {}

    public UUID getQueueId() {
        return queueId;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public QueueDayStatus getStatus() {
        return status;
    }

    /** Takes the next event sequence number. The caller holds the row lock, so this can't race. */
    public long nextEventSeq() {
        lastEventSeq++;
        return lastEventSeq;
    }

    public void pause() {
        requireStatus(QueueDayStatus.OPEN);
        status = QueueDayStatus.PAUSED;
    }

    public void resume() {
        requireStatus(QueueDayStatus.PAUSED);
        status = QueueDayStatus.OPEN;
    }

    public void close(Instant at) {
        if (status == QueueDayStatus.CLOSED) {
            throw new IllegalStateException("queue day already closed");
        }
        status = QueueDayStatus.CLOSED;
        closedAt = Objects.requireNonNull(at, "at");
    }

    private void requireStatus(QueueDayStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("queue day is " + status + ", expected " + expected);
        }
    }

    public QueueDayView toView() {
        return new QueueDayView(
                getId(), queueId, clinicId, serviceDate, status, lastNumber, lastEventSeq, openedAt, closedAt);
    }
}
