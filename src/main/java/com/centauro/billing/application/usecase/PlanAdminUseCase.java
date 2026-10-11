package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.PlanAdminView;
import com.centauro.billing.application.dto.PlanPriceRequest;
import com.centauro.billing.application.dto.PlanPriceView;
import com.centauro.billing.application.dto.PlanRequest;
import com.centauro.billing.application.dto.PlanUpdateRequest;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.exception.DuplicatePlanException;
import com.centauro.billing.domain.exception.DuplicatePlanPriceException;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanPriceNotFoundException;
import com.centauro.billing.domain.exception.PriceAlreadyEffectiveException;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.Amounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** ABM de planes y precios versionados. Los precios son inmutables; no hay borrado de planes. */
@Service
public class PlanAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlanAdminUseCase.class);

    private final PlanRepositoryPort plans;
    private final SubscriptionViewAssembler assembler;
    private final Clock clock;

    public PlanAdminUseCase(PlanRepositoryPort plans, SubscriptionViewAssembler assembler, Clock clock) {
        this.plans = plans;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlanAdminView> listPlans() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return plans.findAll().stream().map(p -> assembler.planAdminView(p, now)).toList();
    }

    @Transactional
    public PlanAdminView createPlan(PlanRequest request, Actor actor) {
        if (!Plan.CODE_FORMAT.matcher(request.code()).matches()) {
            throw new InvalidBillingRequestException("Código de plan inválido");
        }
        if (plans.existsByCode(request.code())) {
            throw new DuplicatePlanException(request.code());
        }
        Plan plan = Plan.create(request.code(), request.name().trim(), request.description(),
                request.active() == null || request.active(), Boolean.TRUE.equals(request.publicVisible()),
                request.sortOrder() == null ? 0 : request.sortOrder(), request.features(), request.limits());
        Plan saved = plans.save(plan);
        log.info("Plan creado code={} por={}", saved.getCode(), actor.logId());
        return assembler.planAdminView(saved, OffsetDateTime.now(clock));
    }

    @Transactional
    public PlanAdminView updatePlan(String code, PlanUpdateRequest request, Actor actor) {
        Plan plan = requirePlan(code);
        plan.update(request.name().trim(), request.description(),
                request.active() == null ? plan.isActive() : request.active(),
                request.publicVisible() == null ? plan.isPublicVisible() : request.publicVisible(),
                request.sortOrder() == null ? plan.getSortOrder() : request.sortOrder(),
                request.features() == null ? plan.getFeatures() : request.features(),
                request.limits() == null ? plan.getLimits() : request.limits());
        plans.save(plan);
        log.info("Plan actualizado code={} por={}", code, actor.logId());
        return assembler.planAdminView(requirePlan(code), OffsetDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public List<PlanPriceView> listPrices(String code) {
        return requirePlan(code).getPrices().stream()
                .sorted(Comparator.comparing(PlanPrice::validFrom).reversed())
                .map(assembler::priceView)
                .toList();
    }

    @Transactional
    public PlanPriceView addPrice(String code, PlanPriceRequest request, Actor actor) {
        BigDecimal amount = Amounts.requirePositiveMoney(request.amount());
        if (request.validFrom() == null || !request.validFrom().isAfter(OffsetDateTime.now(clock))) {
            throw new InvalidBillingRequestException("validFrom tiene que ser posterior al momento actual");
        }
        Plan plan = requirePlan(code);
        if (plans.existsPrice(plan.getId(), request.validFrom())) {
            throw new DuplicatePlanPriceException(code);
        }
        PlanPrice saved = plans.savePrice(PlanPrice.create(plan.getId(), amount, request.validFrom(),
                actor.name()));
        log.info("Precio agregado plan={} validFrom={} por={}", code, saved.validFrom(), actor.logId());
        return assembler.priceView(saved);
    }

    @Transactional
    public void deletePrice(String code, UUID priceId, Actor actor) {
        Plan plan = requirePlan(code);
        PlanPrice price = plans.findPrice(priceId)
                .filter(p -> p.planId().equals(plan.getId()))
                .orElseThrow(() -> new PlanPriceNotFoundException(priceId.toString()));
        if (!price.validFrom().isAfter(OffsetDateTime.now(clock))) {
            throw new PriceAlreadyEffectiveException(priceId.toString());
        }
        plans.deletePrice(priceId);
        log.info("Precio futuro borrado plan={} priceId={} por={}", code, priceId, actor.logId());
    }

    private Plan requirePlan(String code) {
        return plans.findByCode(code).orElseThrow(() -> new PlanNotFoundException(code));
    }
}
