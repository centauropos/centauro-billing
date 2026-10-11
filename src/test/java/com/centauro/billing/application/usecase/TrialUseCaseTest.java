package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.TrialResult;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
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
import static com.centauro.billing.support.TestFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrialUseCaseTest {

    /** 2026-10-11 23:30 en Argentina (02:30 UTC del día siguiente). */
    private static final OffsetDateTime LATE_NIGHT = OffsetDateTime.of(2026, 10, 11, 23, 30, 0, 0, AR_OFFSET);

    private InMemorySubscriptionRepository subscriptions;
    private InMemoryPlanRepository plans;
    private InMemoryEventRepository events;
    private TrialUseCase useCase;
    private Plan pro;

    @BeforeEach
    void setUp() {
        subscriptions = new InMemorySubscriptionRepository();
        plans = new InMemoryPlanRepository();
        events = new InMemoryEventRepository();
        pro = plans.put(plan("PRO", false, new BigDecimal("25000")));
        plans.put(plan("BASICO", false, new BigDecimal("10000")));
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
        assertThat(result.planCode()).isEqualTo("PRO");
        assertThat(result.trialEndsAt()).isEqualTo(OffsetDateTime.of(2026, 10, 25, 23, 59, 59, 0, AR_OFFSET));
        Subscription saved = subscriptions.findByTenantId("tenant-1").orElseThrow();
        assertThat(saved.getPlanId()).isEqualTo(pro.getId());
        assertThat(saved.getPriceAmount()).isEqualByComparingTo("25000.00");
        assertThat(events.types()).containsExactly(SubscriptionEventType.TRIAL_STARTED);
    }

    @Test
    void isIdempotentAndDoesNotModifyExistingSubscription() {
        useCase.createTrial(command("tenant-1", null));
        Subscription first = subscriptions.findByTenantId("tenant-1").orElseThrow();

        TrialResult second = useCase.createTrial(command("tenant-1", "BASICO"));

        assertThat(second.created()).isFalse();
        assertThat(second.planCode()).isEqualTo("PRO");
        assertThat(subscriptions.all()).hasSize(1);
        assertThat(subscriptions.findByTenantId("tenant-1").orElseThrow().getPlanId()).isEqualTo(first.getPlanId());
        assertThat(events.events).hasSize(1);
    }

    @Test
    void usesRequestedPlan() {
        TrialResult result = useCase.createTrial(command("tenant-2", "BASICO"));

        assertThat(result.planCode()).isEqualTo("BASICO");
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

    private static TrialUseCase.Command command(String tenantId, String planCode) {
        return new TrialUseCase.Command(tenantId, "Kiosco Sol", "owner@kiosco.com", planCode, "USER_MANAGER",
                Actor.internal("USER_MANAGER"), null);
    }
}
