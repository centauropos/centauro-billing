package com.centauro.billing.infrastructure.jpa.repository;

import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.infrastructure.jpa.entity.GatewayCheckoutEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayCheckoutJpaRepository extends JpaRepository<GatewayCheckoutEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GatewayCheckoutEntity c where c.id = :id")
    Optional<GatewayCheckoutEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "SELECT * FROM gateway_checkouts WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<GatewayCheckoutEntity> findByIdForUpdateSkipLocked(@Param("id") UUID id);

    Optional<GatewayCheckoutEntity> findByGatewayAndGatewayPreapprovalId(GatewayName gateway, String preapprovalId);

    Optional<GatewayCheckoutEntity> findFirstBySubscriptionIdAndStatus(UUID subscriptionId, CheckoutStatus status);

    List<GatewayCheckoutEntity> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);

    @Query(value = """
            SELECT id FROM gateway_checkouts
            WHERE status = 'PENDING' AND expires_at <= :now AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findExpiredPending(@Param("now") OffsetDateTime now, @Param("afterId") UUID afterId,
                                  @Param("limit") int limit);

    @Query(value = """
            SELECT id FROM gateway_checkouts
            WHERE status IN ('SUPERSEDED', 'EXPIRED', 'CANCELLED', 'FAILED') AND gateway_preapproval_id IS NOT NULL
              AND updated_at >= :since AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findStaleWithPreapproval(@Param("since") OffsetDateTime since, @Param("afterId") UUID afterId,
                                        @Param("limit") int limit);
}
