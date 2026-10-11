package com.centauro.billing.support;

import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.port.PlanRepositoryPort;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryPlanRepository implements PlanRepositoryPort {

    private final Map<UUID, Plan> plans = new LinkedHashMap<>();
    private final List<PlanPrice> prices = new ArrayList<>();

    public Plan put(Plan plan) {
        plans.put(plan.getId(), plan);
        prices.removeIf(p -> p.planId().equals(plan.getId()));
        prices.addAll(plan.getPrices());
        return plan;
    }

    @Override
    public Optional<Plan> findByCode(String code) {
        return plans.values().stream().filter(p -> p.getCode().equals(code)).findFirst().map(this::withPrices);
    }

    @Override
    public Optional<Plan> findById(UUID id) {
        return Optional.ofNullable(plans.get(id)).map(this::withPrices);
    }

    @Override
    public List<Plan> findAll() {
        return plans.values().stream().sorted(Comparator.comparingInt(Plan::getSortOrder))
                .map(this::withPrices).toList();
    }

    @Override
    public boolean existsByCode(String code) {
        return plans.values().stream().anyMatch(p -> p.getCode().equals(code));
    }

    @Override
    public Plan save(Plan plan) {
        plans.put(plan.getId(), plan);
        return withPrices(plan);
    }

    @Override
    public PlanPrice savePrice(PlanPrice price) {
        prices.add(price);
        return price;
    }

    @Override
    public boolean existsPrice(UUID planId, OffsetDateTime validFrom) {
        return prices.stream().anyMatch(p -> p.planId().equals(planId) && p.validFrom().isEqual(validFrom));
    }

    @Override
    public Optional<PlanPrice> findPrice(UUID priceId) {
        return prices.stream().filter(p -> p.id().equals(priceId)).findFirst();
    }

    @Override
    public void deletePrice(UUID priceId) {
        prices.removeIf(p -> p.id().equals(priceId));
    }

    private Plan withPrices(Plan p) {
        return new Plan(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.isActive(), p.isPublicVisible(),
                p.getSortOrder(), p.getFeatures(), p.getLimits(),
                prices.stream().filter(pr -> pr.planId().equals(p.getId())).toList());
    }
}