package com.centauro.billing.infrastructure.jpa.repository;

import com.centauro.billing.domain.enums.PaymentSource;
import com.centauro.billing.infrastructure.jpa.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("update PaymentEntity p set p.applied = true, p.updatedAt = :now where p.id = :id and p.applied = false")
    int markApplied(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    Optional<PaymentEntity> findByTenantIdAndSourceAndReference(String tenantId, PaymentSource source,
                                                                String reference);

    List<PaymentEntity> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    Optional<PaymentEntity> findBySourceAndGatewayPaymentId(PaymentSource source, String gatewayPaymentId);
}
