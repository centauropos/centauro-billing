package com.centauro.billing.infrastructure.adapter;

import com.centauro.billing.domain.enums.BillingPeriod;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.infrastructure.jpa.entity.PlanEntity;
import com.centauro.billing.infrastructure.jpa.entity.PlanPriceEntity;
import com.centauro.billing.infrastructure.jpa.repository.PlanJpaRepository;
import com.centauro.billing.infrastructure.jpa.repository.PlanPriceJpaRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PlanRepositoryAdapter implements PlanRepositoryPort {

    private final PlanJpaRepository planRepository;
    private final PlanPriceJpaRepository priceRepository;

    public PlanRepositoryAdapter(PlanJpaRepository planRepository, PlanPriceJpaRepository priceRepository) {
        this.planRepository = planRepository;
        this.priceRepository = priceRepository;
    }

    @Override
    public Optional<Plan> findByCode(String code) {
        return planRepository.findByCode(code).map(this::withPrices);
    }

    @Override
    public Optional<Plan> findById(UUID id) {
        return planRepository.findById(id).map(this::withPrices);
    }

    @Override
    public List<Plan> findAll() {
        List<PlanEntity> entities = planRepository.findAllByOrderBySortOrderAscCodeAsc();
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PlanPrice>> pricesByPlan = priceRepository
                .findByPlanIdIn(entities.stream().map(PlanEntity::getId).toList()).stream()
                .map(PlanRepositoryAdapter::toDomain)
                .collect(Collectors.groupingBy(PlanPrice::planId));
        return entities.stream()
                .map(e -> toDomain(e, pricesByPlan.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    @Override
    public boolean existsByCode(String code) {
        return planRepository.existsByCode(code);
    }

    @Override
    public Plan save(Plan plan) {
        PlanEntity entity = planRepository.findById(plan.getId()).orElseGet(() -> {
            PlanEntity created = new PlanEntity();
            created.setId(plan.getId());
            created.setCode(plan.getCode());
            return created;
        });
        entity.setName(plan.getName());
        entity.setDescription(plan.getDescription());
        entity.setActive(plan.isActive());
        entity.setPublicVisible(plan.isPublicVisible());
        entity.setSortOrder(plan.getSortOrder());
        entity.setFeatures(new ArrayList<>(plan.getFeatures()));
        entity.setLimits(new LinkedHashMap<>(plan.getLimits()));
        return withPrices(planRepository.save(entity));
    }

    @Override
    public PlanPrice savePrice(PlanPrice price) {
        PlanPriceEntity entity = new PlanPriceEntity();
        entity.setId(price.id());
        entity.setPlanId(price.planId());
        entity.setCurrency(price.currency());
        entity.setBillingPeriod(price.period().name());
        entity.setAmount(price.amount());
        entity.setValidFrom(price.validFrom());
        entity.setCreatedBy(price.createdBy());
        return toDomain(priceRepository.save(entity));
    }

    @Override
    public boolean existsPrice(UUID planId, OffsetDateTime validFrom) {
        return priceRepository.existsByPlanIdAndValidFrom(planId, validFrom);
    }

    @Override
    public Optional<PlanPrice> findPrice(UUID priceId) {
        return priceRepository.findById(priceId).map(PlanRepositoryAdapter::toDomain);
    }

    @Override
    public void deletePrice(UUID priceId) {
        priceRepository.deleteById(priceId);
    }

    private Plan withPrices(PlanEntity entity) {
        return toDomain(entity, priceRepository.findByPlanId(entity.getId()).stream()
                .map(PlanRepositoryAdapter::toDomain).toList());
    }

    private static Plan toDomain(PlanEntity e, List<PlanPrice> prices) {
        return new Plan(e.getId(), e.getCode(), e.getName(), e.getDescription(), e.isActive(), e.isPublicVisible(),
                e.getSortOrder(), e.getFeatures() == null ? null : new LinkedHashSet<>(e.getFeatures()),
                e.getLimits(), prices);
    }

    private static PlanPrice toDomain(PlanPriceEntity e) {
        return new PlanPrice(e.getId(), e.getPlanId(), e.getCurrency(), BillingPeriod.valueOf(e.getBillingPeriod()),
                e.getAmount(), e.getValidFrom(), e.getCreatedBy());
    }
}
