package com.centauro.billing.infrastructure.jpa.repository;

import com.centauro.billing.infrastructure.jpa.entity.PlanPriceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlanPriceJpaRepository extends JpaRepository<PlanPriceEntity, UUID> {

    List<PlanPriceEntity> findByPlanId(UUID planId);

    List<PlanPriceEntity> findByPlanIdIn(Collection<UUID> planIds);

    boolean existsByPlanIdAndValidFrom(UUID planId, OffsetDateTime validFrom);
}