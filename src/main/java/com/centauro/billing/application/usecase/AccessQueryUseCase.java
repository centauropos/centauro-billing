package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.AccessView;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.TenantIds;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Nivel de acceso para los demás servicios. Solo lectura: nunca crea suscripciones. */
@Service
public class AccessQueryUseCase {

    private final SubscriptionRepositoryPort subscriptions;
    private final PlanRepositoryPort plans;
    private final SubscriptionViewAssembler assembler;
    private final Clock clock;

    public AccessQueryUseCase(SubscriptionRepositoryPort subscriptions, PlanRepositoryPort plans,
                              SubscriptionViewAssembler assembler, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccessView access(String tenantId) {
        TenantIds.requireValid(tenantId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = subscriptions.findByTenantId(tenantId).orElse(null);
        Plan plan = subscription == null ? null : plans.findById(subscription.getPlanId()).orElse(null);
        return assembler.accessView(tenantId, subscription, plan, now);
    }
}
