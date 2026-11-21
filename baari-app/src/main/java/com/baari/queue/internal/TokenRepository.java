package com.baari.queue.internal;

import com.baari.queue.TokenStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TokenRepository extends JpaRepository<TokenEntity, UUID> {

    List<TokenEntity> findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(
            UUID queueDayId, Collection<TokenStatus> statuses);

    Optional<TokenEntity> findByQueueDayIdAndIdempotencyKey(UUID queueDayId, UUID idempotencyKey);

    Optional<TokenEntity> findFirstByQueueDayIdAndPhoneHashAndStatusIn(
            UUID queueDayId, byte[] phoneHash, Collection<TokenStatus> statuses);

    long countByQueueDayIdAndPhoneHash(UUID queueDayId, byte[] phoneHash);

    Optional<TokenEntity> findByPublicCode(String publicCode);

    List<TokenEntity> findByQueueDayIdOrderByNumberAsc(UUID queueDayId);

    long countByQueueDayIdAndStatusIn(UUID queueDayId, Collection<TokenStatus> statuses);

    @Query("""
            select new com.baari.queue.internal.TokenRef(t.id, t.queueDayId, d.queueId, t.clinicId)
              from TokenEntity t, QueueDayEntity d
             where t.id = :id and d.id = t.queueDayId
            """)
    Optional<TokenRef> findRefById(@Param("id") UUID id);

    @Query("""
            select new com.baari.queue.internal.TokenRef(t.id, t.queueDayId, d.queueId, t.clinicId)
              from TokenEntity t, QueueDayEntity d
             where t.publicCode = :publicCode and d.id = t.queueDayId
            """)
    Optional<TokenRef> findRefByPublicCode(@Param("publicCode") String publicCode);
}
