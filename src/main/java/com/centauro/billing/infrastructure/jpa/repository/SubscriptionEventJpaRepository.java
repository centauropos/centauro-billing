package com.centauro.billing.infrastructure.jpa.repository;

import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.infrastructure.jpa.entity.SubscriptionEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionEventJpaRepository extends JpaRepository<SubscriptionEventEntity, UUID> {

    boolean existsBySubscriptionIdAndDedupeKey(UUID subscriptionId, String dedupeKey);

    List<SubscriptionEventEntity> findBySubscriptionIdOrderByOccurredAtDesc(UUID subscriptionId, Pageable pageable);

    Optional<SubscriptionEventEntity> findFirstBySubscriptionIdAndDedupeKey(UUID subscriptionId, String dedupeKey);

    Optional<SubscriptionEventEntity> findFirstBySubscriptionIdAndTypeOrderByOccurredAtDesc(
            UUID subscriptionId, SubscriptionEventType type);
}