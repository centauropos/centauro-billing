package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.PlatformCancelRequest;
import com.centauro.billing.application.dto.PlatformChangePlanRequest;
import com.centauro.billing.application.dto.ReactivateRequest;
import com.centauro.billing.application.dto.SuspendRequest;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.PaymentApplier;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.PaymentSource;
import com.centauro.billing.domain.enums.PaymentStatus;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspendMode;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.InvalidSubscriptionTransitionException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.FakePaymentGateway;
import com.centauro.billing.support.TestFixtures;
import com.centauro.billing.support.InMemoryCheckoutRepository;
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
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
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
    private InMemoryPaymentRepository payments;
    private InMemoryEventRepository events;
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
        SubscriptionViewAssembler assembler = TestFixtures.assembler();
        payments = new InMemoryPaymentRepository();
        events = new InMemoryEventRepository();
        useCase = new PlatformSubscriptionUseCase(subscriptions, plans, payments, events,
                new InMemoryCheckoutRepository(), new GatewayOperations(Optional.<PaymentGateway>of(gateway)),
                new PaymentApplier(subscriptions, payments, events), new PeriodCalculator(AR), assembler,
                clockAt(NOW));
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

    @Test
    void suspendPausesTheDebitAndReactivateResumesItOnlyAfterCommit() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(5)).gatewaySubscriptionId("pre-9").gatewayStatus("authorized").build());

        useCase.suspend("tenant-1", new SuspendRequest(SuspendMode.BLOCKED, "fraude"), ADMIN);
        assertThat(gateway.paused).isEmpty();
        commit();
        assertThat(gateway.paused).containsExactly("pre-9");

        useCase.reactivate("tenant-1", new ReactivateRequest(NOW.plusDays(20), "aclarado"), ADMIN);
        assertThat(gateway.resumed).isEmpty();
        commit();
        assertThat(gateway.resumed).containsExactly("pre-9");
    }

    @Test
    void aGatewayFailureAfterCommitDoesNotUndoTheSuspension() {
        Subscription s = subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(5)).gatewaySubscriptionId("pre-9").gatewayStatus("authorized").build();
        subscriptions.put(s);
        gateway.failure = new IllegalStateException("MP caído");

        useCase.suspend("tenant-1", new SuspendRequest(SuspendMode.READ_ONLY, "revisión"), ADMIN);
        commit();

        assertThat(s.isAdminSuspended()).isTrue();
        assertThat(gateway.paused).isEmpty();
    }

    @Test
    void reactivatingFromCancellationDoesNotResumeTheDebit() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.CANCELED)
                .gatewaySubscriptionId("pre-9").gatewayStatus("cancelled").build());

        useCase.reactivate("tenant-1", new ReactivateRequest(NOW.plusDays(20), "vuelve"), ADMIN);
        commit();

        assertThat(gateway.resumed).isEmpty();
    }

    @Test
    void reactivateAppliesThePaymentsReceivedDuringTheSuspensionInOrder() {
        Subscription s = subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_BLOCK).priceAmount(new BigDecimal("59900")).build();
        subscriptions.put(s);
        Payment first = gatewayPayment(s, "ap-1", NOW.minusDays(10));
        Payment second = gatewayPayment(s, "ap-2", NOW.minusDays(3));
        Payment superseded = gatewayPayment(s, "ap-3", NOW.minusDays(2));
        Payment shortAmount = gatewayPayment(s, "ap-4", NOW.minusDays(1));
        events.append(s.recordGatewayNotice(SubscriptionEventType.PAYMENT_FROM_SUPERSEDED_DEBIT, Map.of(),
                "PAYMENT_FROM_SUPERSEDED_DEBIT:ap-3", ADMIN, null, NOW));
        events.append(s.recordGatewayNotice(SubscriptionEventType.PAYMENT_AMOUNT_MISMATCH, Map.of(),
                "PAYMENT_AMOUNT_MISMATCH:ap-4", ADMIN, null, NOW));

        useCase.reactivate("tenant-1", new ReactivateRequest(null, "aclarado"), ADMIN);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(payments.payments.get(first.id()).applied()).isTrue();
        assertThat(payments.payments.get(second.id()).applied()).isTrue();
        assertThat(payments.payments.get(superseded.id()).applied()).isFalse();
        assertThat(payments.payments.get(shortAmount.id()).applied()).isFalse();
        // Dos pagos: dos meses desde hoy, el segundo a continuación del primero.
        assertThat(payments.payments.get(first.id()).periodStart()).isAtSameInstantAs(NOW);
        assertThat(payments.payments.get(second.id()).periodStart())
                .isAtSameInstantAs(payments.payments.get(first.id()).periodEnd());
        assertThat(s.getCurrentPeriodEnd()).isAtSameInstantAs(payments.payments.get(second.id()).periodEnd());
        assertThat(s.getCurrentPeriodEnd()).isAtSameInstantAs(NOW.plusMonths(2));
    }

    @Test
    void reactivateWithoutPeriodEndRequiresPendingPayments() {
        subscriptions.put(subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_BLOCK).build());

        assertThatThrownBy(() -> useCase.reactivate("tenant-1", new ReactivateRequest(null, "x"), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
    }

    @Test
    void anExplicitPeriodEndIsKeptAndPendingPaymentsExtendIt() {
        Subscription s = subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY).build();
        subscriptions.put(s);
        Payment pending = gatewayPayment(s, "ap-1", NOW.minusDays(4));

        useCase.reactivate("tenant-1", new ReactivateRequest(NOW.plusDays(10), "aclarado"), ADMIN);

        assertThat(payments.payments.get(pending.id()).periodStart()).isAtSameInstantAs(NOW.plusDays(10));
        assertThat(s.getCurrentPeriodEnd()).isAtSameInstantAs(payments.payments.get(pending.id()).periodEnd());
    }

    private Payment gatewayPayment(Subscription s, String id, OffsetDateTime paidAt) {
        return payments.save(Payment.fromGateway(s, PaymentSource.MERCADOPAGO, PaymentStatus.APPROVED,
                new BigDecimal("59900"), "ARS", id, "pay-" + id, "approved", paidAt, "webhook", paidAt));
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
    }
}
