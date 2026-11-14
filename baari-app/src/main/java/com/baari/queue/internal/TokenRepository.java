package com.baari.queue.internal;

import com.baari.queue.TokenStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenRepository extends JpaRepository<TokenEntity, UUID> {

    List<TokenEntity> findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(
            UUID queueDayId, Collection<TokenStatus> statuses);

    Optional<TokenEntity> findByQueueDayIdAndIdempotencyKey(UUID queueDayId, UUID idempotencyKey);

    Optional<TokenEntity> findFirstByQueueDayIdAndPhoneHashAndStatusIn(
            UUID queueDayId, byte[] phoneHash, Collection<TokenStatus> statuses);

    long countByQueueDayIdAndStatusIn(UUID queueDayId, Collection<TokenStatus> statuses);
}
