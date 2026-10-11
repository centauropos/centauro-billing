package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.PlatformCancelRequest;
import com.centauro.billing.application.dto.PlatformChangePlanRequest;
import com.centauro.billing.application.dto.ReactivateRequest;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.InvalidSubscriptionTransitionException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.FakePaymentGateway;
import com.centauro.billing.support.InMemoryEventRepository;
import com.centauro.billing.support.InMemoryPaymentRepository;
import com.centauro.billing.support.InMemoryPlanRepository;
import com.centauro.billing.support.InMemorySubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Optional;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.customPlan;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformSubscriptionUseCaseTest {

    private static final Actor ADMIN = Actor.platformAdmin("admin", "42");

    private InMemorySubscriptionRepository subscriptions;
    private InMemoryPlanRepository plans;
    private FakePaymentGateway gateway;
    private PlatformSubscriptionUseCase useCase;
    private Plan pro;

    @BeforeEach
    void setUp() {
        subscriptions = new InMemorySubscriptionRepository();
        plans = new InMemoryPlanRepository();
        gateway = new FakePaymentGateway();
        pro = plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        Plan legacy = plan("LEGACY", false, new BigDecimal("5000"));
        legacy.update(legacy.getName(), null, false, false, false, 0, legacy.getFeatures(), legacy.getLimits());
        plans.put(legacy);
        plans.put(plan("INTERNO", false, new BigDecimal("1")));
        plans.put(customPlan("EMPRESA", true));
        SubscriptionViewAssembler assembler = new SubscriptionViewAssembler(new AccessPolicy(TERMS),
                new NoticePolicy(AR), new AllowedActionsPolicy(), TERMS);
        useCase = new PlatformSubscriptionUseCase(subscriptions, plans, new InMemoryPaymentRepository(),
                new InMemoryEventRepository(), new GatewayOperations(Optional.<PaymentGateway>of(gateway)),
                new PeriodCalculator(AR), assembler, clockAt(NOW));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void changePlanOnlyAcceptsActivePlansEvenIfNotPublic() {
        subscriptions.put(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> useCase.changePlan("tenant-1",
                new PlatformChangePlanRequest("LEGACY", "x", null, null), ADMIN)).isInstanceOf(PlanNotFoundException.class);

        assertThat(useCase.changePlan("tenant-1", new PlatformChangePlanRequest("INTERNO", "x", null, null), ADMIN)
                .plan().code()).isEqualTo("INTERNO");
    }

    @Test
    void changePlanToCustomPlanStoresTheCustomAmountAndSyncsItToTheGateway() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(5)).gatewaySubscriptionId("pre-3").build());

        TenantSubscriptionView view = useCase.changePlan("tenant-1",
                new PlatformChangePlanRequest("EMPRESA", "acuerdo comercial", null, new BigDecimal("180000")), ADMIN);

        assertThat(view.plan().code()).isEqualTo("EMPRESA");
        assertThat(view.plan().customPricing()).isTrue();
        assertThat(view.price().amount()).isEqualTo("180000.00");
        assertThat(subscriptions.findByTenantId("tenant-1").orElseThrow().getPriceAmount())
                .isEqualByComparingTo("180000.00");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(gateway.amountUpdates).containsExactly("pre-3:180000.00");
    }

    @Test
    void changePlanToCustomPlanRequiresAValidCustomAmount() {
        subscriptions.put(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> useCase.changePlan("tenant-1",
                new PlatformChangePlanRequest("EMPRESA", "x", null, null), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> useCase.changePlan("tenant-1",
                new PlatformChangePlanRequest("EMPRESA", "x", null, new BigDecimal("-1")), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThat(subscriptions.findByTenantId("tenant-1").orElseThrow().getPlanId()).isEqualTo(pro.getId());
    }

    @Test
    void customAmountOnListPricePlanIsRejected() {
        subscriptions.put(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> useCase.changePlan("tenant-1",
                new PlatformChangePlanRequest("INTERNO", "x", null, new BigDecimal("100")), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThat(subscriptions.findByTenantId("tenant-1").orElseThrow().getPlanId()).isEqualTo(pro.getId());
    }

    @Test
    void cancelAndChangePlanCallTheGatewayOnlyAfterCommit() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(5)).gatewaySubscriptionId("pre-1").build());

        useCase.changePlan("tenant-1", new PlatformChangePlanRequest("INTERNO", "x", null, null), ADMIN);
        useCase.cancel("tenant-1", new PlatformCancelRequest(true, "fraude"), ADMIN);
        assertThat(gateway.amountUpdates).isEmpty();
        assertThat(gateway.canceled).isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(gateway.amountUpdates).containsExactly("pre-1:1.00");
        assertThat(gateway.canceled).containsExactly("pre-1");
    }

    @Test
    void failedCancelNeverReachesTheGateway() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.CANCELED)
                .gatewaySubscriptionId("pre-2").build());

        assertThatThrownBy(() -> useCase.cancel("tenant-1", new PlatformCancelRequest(true, "x"), ADMIN))
                .isInstanceOf(InvalidSubscriptionTransitionException.class);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        assertThat(gateway.canceled).isEmpty();
    }

    @Test
    void reactivateAnchorsOnTheNewPeriodEnd() {
        Subscription s = subscription().planId(pro.getId()).status(SubscriptionStatus.CANCELED).build();
        subscriptions.put(s);

        useCase.reactivate("tenant-1", new ReactivateRequest(NOW.plusDays(20), "acuerdo"), ADMIN);

        assertThat(s.getBillingAnchorDay()).isEqualTo(NOW.plusDays(20).getDayOfMonth());
    }
}
