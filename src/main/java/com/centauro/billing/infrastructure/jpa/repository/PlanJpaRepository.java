package com.centauro.billing.infrastructure.jpa.repository;

import com.centauro.billing.infrastructure.jpa.entity.PlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanJpaRepository extends JpaRepository<PlanEntity, UUID> {

    Optional<PlanEntity> findByCode(String code);

    boolean existsByCode(String code);

    List<PlanEntity> findAllByOrderBySortOrderAscCodeAsc();
}