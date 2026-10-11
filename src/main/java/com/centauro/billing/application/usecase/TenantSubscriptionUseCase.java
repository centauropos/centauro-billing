package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.PriceView;
import com.centauro.billing.application.dto.PublicPlanView;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.exception.InvalidSubscriptionTransitionException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanWithoutPriceException;
import com.centauro.billing.domain.exception.SubscriptionNotFoundException;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.model.SubscriptionEvent;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Operaciones del tenant sobre su propia suscripción. El tenantId siempre viene del JWT. */
@Service
public class TenantSubscriptionUseCase {

    private final SubscriptionRepositoryPort subscriptions;
    private final PlanRepositoryPort plans;
    private final SubscriptionEventRepositoryPort events;
    private final GatewayOperations gatewayOperations;
    private final SubscriptionViewAssembler assembler;
    private final Clock clock;

    public TenantSubscriptionUseCase(SubscriptionRepositoryPort subscriptions, PlanRepositoryPort plans,
                                     SubscriptionEventRepositoryPort events, GatewayOperations gatewayOperations,
                                     SubscriptionViewAssembler assembler, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.events = events;
        this.gatewayOperations = gatewayOperations;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TenantSubscriptionView getSubscription(String tenantId) {
        Subscription subscription = subscriptions.findByTenantId(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
        return view(subscription, OffsetDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public List<PublicPlanView> listPlans(String tenantId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID currentPlanId = subscriptions.findByTenantId(tenantId).map(Subscription::getPlanId).orElse(null);
        return plans.findAll().stream()
                .filter(Plan::isOfferable)
                .flatMap(plan -> plan.currentPrice(now).stream().map(price -> new PublicPlanView(
                        plan.getCode(), plan.getName(), plan.getDescription(), plan.getFeatures(), plan.getLimits(),
                        PriceView.monthly(price.amount(), price.currency()),
                        plan.getId().equals(currentPlanId))))
                .toList();
    }

    @Transactional
    public TenantSubscriptionView cancel(String tenantId, String reason, Actor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = lock(tenantId);
        gatewayOperations.requireAvailable(subscription);
        SubscriptionEvent event = subscription.cancel(actor, reason, now);
        Subscription saved = subscriptions.save(subscription);
        events.append(event);
        gatewayOperations.cancelAfterCommit(saved);
        return view(saved, now);
    }

    @Transactional
    public TenantSubscriptionView changePlan(String tenantId, String planCode, Actor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = lock(tenantId);
        if (subscription.isAdminSuspended()) {
            throw new InvalidSubscriptionTransitionException(
                    "No se puede cambiar el plan de una suscripción suspendida por la plataforma");
        }
        Plan plan = plans.findByCode(planCode)
                .filter(Plan::isOfferable)
                .orElseThrow(() -> new PlanNotFoundException(planCode));
        PlanPrice price = plan.currentPrice(now).orElseThrow(() -> new PlanWithoutPriceException(planCode));
        gatewayOperations.requireAvailable(subscription);
        SubscriptionEvent event = subscription.changePlan(plan.getId(), plan.getCode(), price.amount(), actor,
                null, now);
        Subscription saved = subscriptions.save(subscription);
        events.append(event);
        gatewayOperations.updateAmountAfterCommit(saved, price.amount());
        return view(saved, now);
    }

    private Subscription lock(String tenantId) {
        return subscriptions.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
    }

    private TenantSubscriptionView view(Subscription subscription, OffsetDateTime now) {
        Optional<Plan> plan = plans.findById(subscription.getPlanId());
        return assembler.tenantView(subscription, plan.orElse(null), now);
    }
}
