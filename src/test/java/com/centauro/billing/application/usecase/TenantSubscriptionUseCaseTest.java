package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.PublicPlanView;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.exception.InvalidSubscriptionTransitionException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanWithoutPriceException;
import com.centauro.billing.domain.exception.SamePlanException;
import com.centauro.billing.domain.exception.SubscriptionNotFoundException;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.FakePaymentGateway;
import com.centauro.billing.support.InMemoryEventRepository;
import com.centauro.billing.support.InMemoryPlanRepository;
import com.centauro.billing.support.InMemorySubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantSubscriptionUseCaseTest {

    private static final Actor OWNER = Actor.tenantUser("owner");

    private InMemorySubscriptionRepository subscriptions;
    private InMemoryPlanRepository plans;
    private InMemoryEventRepository events;
    private FakePaymentGateway gateway;
    private Plan pro;
    private Plan basico;

    @BeforeEach
    void setUp() {
        subscriptions = new InMemorySubscriptionRepository();
        plans = new InMemoryPlanRepository();
        events = new InMemoryEventRepository();
        gateway = new FakePaymentGateway();
        pro = plans.put(plan("PRO", true, new BigDecimal("25000")));
        basico = plans.put(plan("BASICO", true, new BigDecimal("10000")));
    }

    private TenantSubscriptionUseCase useCase(Optional<FakePaymentGateway> withGateway) {
        SubscriptionViewAssembler assembler = new SubscriptionViewAssembler(new AccessPolicy(TERMS),
                new NoticePolicy(AR), new AllowedActionsPolicy(), TERMS);
        return new TenantSubscriptionUseCase(subscriptions, plans, events,
                new GatewayOperations(withGateway.<PaymentGateway>map(g -> g)), assembler, clockAt(NOW));
    }

    @Test
    void getSubscriptionBuildsTheTenantView() {
        subscriptions.put(subscription().planId(pro.getId()).build());

        TenantSubscriptionView view = useCase(Optional.empty()).getSubscription("tenant-1");

        assertThat(view.status()).isEqualTo("TRIALING");
        assertThat(view.accessLevel()).isEqualTo("FULL");
        assertThat(view.plan().code()).isEqualTo("PRO");
        assertThat(view.price().amount()).isEqualTo("25000.00");
        assertThat(view.trialDaysLeft()).isEqualTo(14);
        assertThat(view.notice().code()).isEqualTo("TRIAL_ACTIVE");
        assertThat(view.allowedActions()).containsExactly("CHANGE_PLAN", "CANCEL");
        assertThat(view.pendingCheckout()).isNull();
        assertThat(view.trialEndsAt().getOffset()).isEqualTo(NOW.getOffset());
    }

    @Test
    void missingSubscriptionIsNotFound() {
        assertThatThrownBy(() -> useCase(Optional.empty()).getSubscription("tenant-x"))
                .isInstanceOf(SubscriptionNotFoundException.class);
    }

    @Test
    void plansListOnlyOfferablePlansWithPriceAndMarksCurrent() {
        plans.put(plan("OCULTO", false, new BigDecimal("1")));
        plans.put(plan("SINPRECIO", true, null));
        subscriptions.put(subscription().planId(pro.getId()).build());

        List<PublicPlanView> result = useCase(Optional.empty()).listPlans("tenant-1");

        assertThat(result).extracting(PublicPlanView::code).containsExactlyInAnyOrder("PRO", "BASICO");
        assertThat(result).filteredOn(PublicPlanView::current).extracting(PublicPlanView::code).containsExactly("PRO");
    }

    @Test
    void cancelWithoutGatewaySchedulesAndCallsNothing() {
        subscriptions.put(subscription().planId(pro.getId()).build());

        TenantSubscriptionView view = useCase(Optional.empty()).cancel("tenant-1", "caro", OWNER);

        assertThat(view.cancelAtPeriodEnd()).isTrue();
        assertThat(view.notice().code()).isEqualTo("CANCELLATION_SCHEDULED");
        assertThat(view.allowedActions()).doesNotContain("CANCEL");
    }

    @Test
    void cancelWithGatewaySubscriptionCancelsAtGateway() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-123").build());

        useCase(Optional.of(gateway)).cancel("tenant-1", null, OWNER);

        assertThat(gateway.canceled).containsExactly("pre-123");
    }

    @Test
    void withActiveTransactionTheGatewayIsCalledOnlyAfterCommit() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-123").build());
        TransactionSynchronizationManager.initSynchronization();
        try {
            useCase(Optional.of(gateway)).cancel("tenant-1", null, OWNER);
            assertThat(gateway.canceled).isEmpty();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(gateway.canceled).containsExactly("pre-123");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackNeverReachesTheGateway() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-9").build());
        TransactionSynchronizationManager.initSynchronization();
        try {
            useCase(Optional.of(gateway)).changePlan("tenant-1", "BASICO", OWNER);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertThat(gateway.amountUpdates).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void failedOperationDoesNotCallOrScheduleTheGateway() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.CANCELED)
                .gatewaySubscriptionId("pre-7").build());
        TenantSubscriptionUseCase useCase = useCase(Optional.of(gateway));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> useCase.cancel("tenant-1", null, OWNER))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
            assertThatThrownBy(() -> useCase.changePlan("tenant-1", "BASICO", OWNER))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(gateway.canceled).isEmpty();
        assertThat(gateway.amountUpdates).isEmpty();
    }

    @Test
    void gatewayErrorAfterCommitIsLoggedAndDoesNotPropagate() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-1").build());
        gateway.failure = new IllegalStateException("MP caído");
        TransactionSynchronizationManager.initSynchronization();
        try {
            TenantSubscriptionView view = useCase(Optional.of(gateway)).cancel("tenant-1", null, OWNER);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(view.cancelAtPeriodEnd()).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void gatewaySubscriptionWithoutConfiguredGatewayFails() {
        Subscription s = subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-123").build();
        subscriptions.put(s);

        assertThatThrownBy(() -> useCase(Optional.empty()).cancel("tenant-1", null, OWNER))
                .isInstanceOf(GatewayUnavailableException.class);
        assertThat(events.events).isEmpty();
    }

    @Test
    void cancelTwiceIsInvalidTransition() {
        subscriptions.put(subscription().planId(pro.getId()).build());
        TenantSubscriptionUseCase useCase = useCase(Optional.empty());
        useCase.cancel("tenant-1", null, OWNER);

        assertThatThrownBy(() -> useCase.cancel("tenant-1", null, OWNER))
                .isInstanceOf(InvalidSubscriptionTransitionException.class);
    }

    @Test
    void changePlanUpdatesPlanAndPriceSnapshot() {
        Subscription s = subscription().planId(pro.getId()).build();
        subscriptions.put(s);

        TenantSubscriptionView view = useCase(Optional.empty()).changePlan("tenant-1", "BASICO", OWNER);

        assertThat(view.plan().code()).isEqualTo("BASICO");
        assertThat(view.price().amount()).isEqualTo("10000.00");
        assertThat(s.getPlanId()).isEqualTo(basico.getId());
    }

    @Test
    void changePlanWithGatewayUpdatesAmount() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-9").build());

        useCase(Optional.of(gateway)).changePlan("tenant-1", "BASICO", OWNER);

        assertThat(gateway.amountUpdates).containsExactly("pre-9:10000.00");
    }

    @Test
    void changePlanErrors() {
        plans.put(plan("OCULTO", false, new BigDecimal("1")));
        plans.put(plan("SINPRECIO", true, null));
        subscriptions.put(subscription().planId(pro.getId()).build());
        TenantSubscriptionUseCase useCase = useCase(Optional.empty());

        assertThatThrownBy(() -> useCase.changePlan("tenant-1", "PRO", OWNER)).isInstanceOf(SamePlanException.class);
        assertThatThrownBy(() -> useCase.changePlan("tenant-1", "OCULTO", OWNER))
                .isInstanceOf(PlanNotFoundException.class);
        assertThatThrownBy(() -> useCase.changePlan("tenant-1", "SINPRECIO", OWNER))
                .isInstanceOf(PlanWithoutPriceException.class);
    }

    @Test
    void changePlanWithAdminSuspensionIsInvalid() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY).build());

        assertThatThrownBy(() -> useCase(Optional.empty()).changePlan("tenant-1", "BASICO", OWNER))
                .isInstanceOf(InvalidSubscriptionTransitionException.class);
    }
}
