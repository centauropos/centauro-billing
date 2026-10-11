package com.centauro.billing.infrastructure.jpa.repository;

import com.centauro.billing.infrastructure.jpa.entity.SubscriptionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, UUID>,
        JpaSpecificationExecutor<SubscriptionEntity> {

    Optional<SubscriptionEntity> findByTenantId(String tenantId);

    Optional<SubscriptionEntity> findFirstByGatewaySubscriptionId(String gatewaySubscriptionId);

    @Query(value = """
            SELECT id FROM subscriptions
            WHERE gateway_subscription_id IS NOT NULL AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findWithGatewaySubscription(@Param("afterId") UUID afterId, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SubscriptionEntity s where s.tenantId = :tenantId")
    Optional<SubscriptionEntity> findByTenantIdForUpdate(@Param("tenantId") String tenantId);

    @Query(value = "SELECT * FROM subscriptions WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<SubscriptionEntity> findByIdForUpdateSkipLocked(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            INSERT INTO subscriptions (id, tenant_id, plan_id, status, trial_ends_at, price_amount, currency,
                                       billing_email, company_name, cancel_at_period_end, version,
                                       created_at, updated_at)
            VALUES (:id, :tenantId, :planId, 'TRIALING', :trialEndsAt, CAST(:priceAmount AS NUMERIC(12,2)), 'ARS',
                    CAST(:billingEmail AS VARCHAR), CAST(:companyName AS VARCHAR), FALSE, 0, :createdAt, :createdAt)
            ON CONFLICT (tenant_id) DO NOTHING
            """, nativeQuery = true)
    int insertTrialIfAbsent(@Param("id") UUID id,
                            @Param("tenantId") String tenantId,
                            @Param("planId") UUID planId,
                            @Param("trialEndsAt") OffsetDateTime trialEndsAt,
                            @Param("priceAmount") BigDecimal priceAmount,
                            @Param("billingEmail") String billingEmail,
                            @Param("companyName") String companyName,
                            @Param("createdAt") OffsetDateTime createdAt);

    @Query(value = """
            SELECT id FROM subscriptions
            WHERE status = 'TRIALING' AND trial_ends_at <= :now AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findTrialsDue(@Param("now") OffsetDateTime now, @Param("afterId") UUID afterId,
                             @Param("limit") int limit);

    @Query(value = """
            SELECT id FROM subscriptions
            WHERE status = 'ACTIVE' AND current_period_end <= :now AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findPeriodsDue(@Param("now") OffsetDateTime now, @Param("afterId") UUID afterId,
                              @Param("limit") int limit);

    @Query(value = """
            SELECT id FROM subscriptions
            WHERE status = 'PAST_DUE' AND grace_ends_at <= :now AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findGraceDue(@Param("now") OffsetDateTime now, @Param("afterId") UUID afterId,
                            @Param("limit") int limit);
}