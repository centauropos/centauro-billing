package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.TrialResult;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanRequiresContactException;
import com.centauro.billing.domain.exception.SubscriptionNotFoundException;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.BillingTerms;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.TenantIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

/** T1: alta idempotente del trial por tenant. */
@Service
public class TrialUseCase {

    private static final Logger log = LoggerFactory.getLogger(TrialUseCase.class);

    private final SubscriptionRepositoryPort subscriptions;
    private final PlanRepositoryPort plans;
    private final SubscriptionEventRepositoryPort events;
    private final PeriodCalculator periodCalculator;
    private final BillingTerms terms;
    private final SubscriptionViewAssembler assembler;
    private final Clock clock;

    public TrialUseCase(SubscriptionRepositoryPort subscriptions, PlanRepositoryPort plans,
                        SubscriptionEventRepositoryPort events, PeriodCalculator periodCalculator,
                        BillingTerms terms, SubscriptionViewAssembler assembler, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.events = events;
        this.periodCalculator = periodCalculator;
        this.terms = terms;
        this.assembler = assembler;
        this.clock = clock;
    }

    /**
     * {@code customPricingAllowed} solo lo habilita la plataforma: el alta interna no puede elegir un plan a
     * medida. {@code customAmount} es obligatorio para esos planes y prohibido para el resto.
     */
    public record Command(String tenantId, String companyName, String billingEmail, String planCode,
                          String source, Actor actor, String reason, BigDecimal customAmount,
                          boolean customPricingAllowed) {

        public static Command internal(String tenantId, String companyName, String billingEmail, String planCode,
                                       String source, Actor actor) {
            return new Command(tenantId, companyName, billingEmail, planCode, source, actor, null, null, false);
        }
    }

    @Transactional
    public TrialResult createTrial(Command command) {
        String tenantId = TenantIds.requireValid(command.tenantId());
        Optional<Subscription> existing = subscriptions.findByTenantId(tenantId);
        if (existing.isPresent()) {
            return result(existing.get(), false);
        }

        String planCode = command.planCode() == null || command.planCode().isBlank()
                ? terms.trialPlanCode() : command.planCode();
        Plan plan = plans.findByCode(planCode)
                .filter(Plan::isActive)
                .orElseThrow(() -> new PlanNotFoundException(planCode));
        if (plan.isCustomPricing() && !command.customPricingAllowed()) {
            throw new PlanRequiresContactException(planCode);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        BigDecimal priceAmount = plan.resolveCustomAmount(command.customAmount())
                .orElseGet(() -> plan.currentPrice(now).map(PlanPrice::amount).orElse(null));
        OffsetDateTime trialEndsAt = periodCalculator.startOfDayAfter(now, terms.trialDays());
        Subscription subscription = Subscription.startTrial(tenantId, plan.getId(), priceAmount, trialEndsAt,
                trim(command.companyName()), trim(command.billingEmail()), now);

        if (subscriptions.insertIfAbsent(subscription)) {
            events.append(subscription.trialStartedEvent(command.actor(), command.reason(), command.source(), now));
            log.info("Trial creado tenant={} plan={} trialEndsAt={}", tenantId, plan.getCode(), trialEndsAt);
            return result(subscription, plan, true);
        }
        Subscription concurrent = subscriptions.findByTenantId(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
        return result(concurrent, false);
    }

    private TrialResult result(Subscription subscription, boolean created) {
        Plan plan = plans.findById(subscription.getPlanId()).orElse(null);
        return result(subscription, plan, created);
    }

    private TrialResult result(Subscription subscription, Plan plan, boolean created) {
        return new TrialResult(subscription.getTenantId(), subscription.getStatus().name(),
                plan == null ? null : plan.getCode(), assembler.local(subscription.getTrialEndsAt()), created);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
