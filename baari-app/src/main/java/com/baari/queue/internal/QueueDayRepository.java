package com.baari.queue.internal;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueueDayRepository extends JpaRepository<QueueDayEntity, UUID> {

    Optional<QueueDayEntity> findByQueueIdAndServiceDate(UUID queueId, LocalDate serviceDate);

    /**
     * Loads the day and takes its row lock until the transaction ends ({@code FOR NO KEY UPDATE} on
     * Postgres). Every change to a day or its tokens takes this lock first (queue day before token,
     * PLAN §6.3), so changes to one day are serialized and can't deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from QueueDayEntity d where d.queueId = :queueId and d.serviceDate = :serviceDate")
    Optional<QueueDayEntity> lockByQueueIdAndServiceDate(
            @Param("queueId") UUID queueId, @Param("serviceDate") LocalDate serviceDate);
}
