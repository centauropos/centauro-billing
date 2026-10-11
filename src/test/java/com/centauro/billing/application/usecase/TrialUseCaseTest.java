package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.TrialResult;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanRequiresContactException;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.InMemoryEventRepository;
import com.centauro.billing.support.InMemoryPlanRepository;
import com.centauro.billing.support.InMemorySubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.AR_OFFSET;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.customPlan;
import static com.centauro.billing.support.TestFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrialUseCaseTest {

    /** 2026-10-11 23:30 en Argentina (02:30 UTC del día siguiente). */
    private static final OffsetDateTime LATE_NIGHT = OffsetDateTime.of(2026, 10, 11, 23, 30, 0, 0, AR_OFFSET);
    private static final Actor ADMIN = Actor.platformAdmin("admin", "42");

    private InMemorySubscriptionRepository subscriptions;
    private InMemoryPlanRepository plans;
    private InMemoryEventRepository events;
    private TrialUseCase useCase;
    private Plan profesional;

    @BeforeEach
    void setUp() {
        subscriptions = new InMemorySubscriptionRepository();
        plans = new InMemoryPlanRepository();
        events = new InMemoryEventRepository();
        profesional = plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        plans.put(plan("INICIAL", true, new BigDecimal("14900")));
        plans.put(customPlan("EMPRESA", true));
        SubscriptionViewAssembler assembler = new SubscriptionViewAssembler(new AccessPolicy(TERMS),
                new NoticePolicy(AR), new AllowedActionsPolicy(), TERMS);
        useCase = new TrialUseCase(subscriptions, plans, events, new PeriodCalculator(AR), TERMS, assembler,
                clockAt(LATE_NIGHT));
    }

    @Test
    void createsTrialWithDefaultPlanAndEndOfArgentineDay() {
        TrialResult result = useCase.createTrial(command("tenant-1", null));

        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo("TRIALING");
        assertThat(result.planCode()).isEqualTo("PROFESIONAL");
        assertThat(result.trialEndsAt()).isEqualTo(OffsetDateTime.of(2026, 10, 26, 0, 0, 0, 0, AR_OFFSET));
        Subscription saved = subscriptions.findByTenantId("tenant-1").orElseThrow();
        assertThat(saved.getPlanId()).isEqualTo(profesional.getId());
        assertThat(saved.getPriceAmount()).isEqualByComparingTo("59900.00");
        assertThat(events.types()).containsExactly(SubscriptionEventType.TRIAL_STARTED);
    }

    @Test
    void isIdempotentAndDoesNotModifyExistingSubscription() {
        useCase.createTrial(command("tenant-1", null));
        Subscription first = subscriptions.findByTenantId("tenant-1").orElseThrow();

        TrialResult second = useCase.createTrial(command("tenant-1", "INICIAL"));

        assertThat(second.created()).isFalse();
        assertThat(second.planCode()).isEqualTo("PROFESIONAL");
        assertThat(subscriptions.all()).hasSize(1);
        assertThat(subscriptions.findByTenantId("tenant-1").orElseThrow().getPlanId()).isEqualTo(first.getPlanId());
        assertThat(events.events).hasSize(1);
    }

    @Test
    void usesRequestedPlan() {
        TrialResult result = useCase.createTrial(command("tenant-2", "INICIAL"));

        assertThat(result.planCode()).isEqualTo("INICIAL");
    }

    @Test
    void unknownPlanFails() {
        assertThatThrownBy(() -> useCase.createTrial(command("tenant-3", "NOPE")))
                .isInstanceOf(PlanNotFoundException.class);
        assertThat(subscriptions.all()).isEmpty();
    }

    @Test
    void invalidTenantIdFormatFails() {
        assertThatThrownBy(() -> useCase.createTrial(command("tenant con espacios", null)))
                .isInstanceOf(InvalidBillingRequestException.class);
    }

    @Test
    void internalTrialCannotUseCustomPricingPlan() {
        assertThatThrownBy(() -> useCase.createTrial(command("tenant-4", "EMPRESA")))
                .isInstanceOf(PlanRequiresContactException.class)
                .hasFieldOrPropertyWithValue("code", "PLAN_REQUIRES_CONTACT");
        assertThat(subscriptions.all()).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void platformTrialOnCustomPlanStoresTheCustomAmount() {
        TrialResult result = useCase.createTrial(platform("tenant-5", "EMPRESA", new BigDecimal("150000.50")));

        assertThat(result.planCode()).isEqualTo("EMPRESA");
        assertThat(subscriptions.findByTenantId("tenant-5").orElseThrow().getPriceAmount())
                .isEqualByComparingTo("150000.50");
    }

    @Test
    void platformTrialOnCustomPlanRequiresAValidCustomAmount() {
        assertThatThrownBy(() -> useCase.createTrial(platform("tenant-6", "EMPRESA", null)))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> useCase.createTrial(platform("tenant-6", "EMPRESA", BigDecimal.ZERO)))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> useCase.createTrial(platform("tenant-6", "EMPRESA", new BigDecimal("1.001"))))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThat(subscriptions.all()).isEmpty();
    }

    @Test
    void customAmountOnListPricePlanIsRejected() {
        assertThatThrownBy(() -> useCase.createTrial(platform("tenant-7", "INICIAL", new BigDecimal("100"))))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThat(subscriptions.all()).isEmpty();
    }

    private static TrialUseCase.Command command(String tenantId, String planCode) {
        return TrialUseCase.Command.internal(tenantId, "Kiosco Sol", "owner@kiosco.com", planCode, "USER_MANAGER",
                Actor.internal("USER_MANAGER"));
    }

    private static TrialUseCase.Command platform(String tenantId, String planCode, BigDecimal customAmount) {
        return new TrialUseCase.Command(tenantId, "Empresa SA", "admin@empresa.com", planCode, "PLATFORM", ADMIN,
                "alta comercial", customAmount, true);
    }
}
