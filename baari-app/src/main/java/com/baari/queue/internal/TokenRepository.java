package com.baari.queue.internal;

import com.baari.queue.TokenStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenRepository extends JpaRepository<TokenEntity, UUID> {

    List<TokenEntity> findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(
            UUID queueDayId, Collection<TokenStatus> statuses);
}
