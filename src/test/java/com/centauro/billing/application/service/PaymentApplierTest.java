package com.centauro.billing.application.service;

import com.centauro.billing.application.dto.ManualPaymentRequest;
import com.centauro.billing.application.dto.ManualPaymentResult;
import com.centauro.billing.application.usecase.ManualPaymentUseCase;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.BillingException;
import com.centauro.billing.domain.exception.DuplicatePaymentException;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.SubscriptionAdminSuspendedException;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.TestFixtures;
import com.centauro.billing.support.InMemoryEventRepository;
import com.centauro.billing.support.InMemoryPaymentRepository;
import com.centauro.billing.support.InMemoryPlanRepository;
import com.centauro.billing.support.InMemorySubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentApplierTest {

    private static final Actor ADMIN = Actor.platformAdmin("admin");

    private InMemorySubscriptionRepository subscriptions;
    private InMemoryPaymentRepository payments;
    private InMemoryEventRepository events;
    private PaymentApplier applier;
    private ManualPaymentUseCase manualPayments;
    private Plan pro;

    @BeforeEach
    void setUp() {
        subscriptions = new InMemorySubscriptionRepository();
        payments = new InMemoryPaymentRepository();
        events = new InMemoryEventRepository();
        InMemoryPlanRepository plans = new InMemoryPlanRepository();
        pro = plans.put(plan("PROFESIONAL", false, new BigDecimal("25000")));
        applier = new PaymentApplier(subscriptions, payments, events);
        SubscriptionViewAssembler assembler = TestFixtures.assembler();
        manualPayments = new ManualPaymentUseCase(subscriptions, payments, plans, applier,
                new PeriodCalculator(AR), assembler, clockAt(NOW));
    }

    @Test
    void appliedFlagMakesApplicationIdempotent() {
        Subscription s = store(subscription().planId(pro.getId()).build());
        Payment payment = payments.save(Payment.manual(s, new BigDecimal("25000"), NOW, NOW, NOW.plusMonths(1),
                "REF-1", null, "admin", NOW));

        PaymentApplier.Result first = applier.apply(s, payment, 11, GatewayName.MANUAL, ADMIN, "x", NOW);
        PaymentApplier.Result second = applier.apply(s, payment, 11, GatewayName.MANUAL, ADMIN, "x", NOW);

        assertThat(first.applied()).isTrue();
        assertThat(second.applied()).isFalse();
        assertThat(payments.payments.get(payment.id()).applied()).isTrue();
        assertThat(events.types()).containsExactly(SubscriptionEventType.PAYMENT_APPLIED);
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void gatewayPaymentWithAdminSuspensionIsRecordedWithoutReactivating() {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY).build());
        Payment payment = payments.save(Payment.manual(s, new BigDecimal("25000"), NOW, NOW, NOW.plusMonths(1),
                "REF-GW", null, "mp", NOW));

        PaymentApplier.Result result = applier.apply(s, payment, 11, GatewayName.MERCADOPAGO, ADMIN, null, NOW);

        assertThat(result.applied()).isFalse();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(events.types()).containsExactly(SubscriptionEventType.PAYMENT_RECORDED);
    }

    @ParameterizedTest
    @EnumSource(value = SuspensionReason.class, names = {"ADMIN_READ_ONLY", "ADMIN_BLOCK"})
    void manualPaymentWithAdminSuspensionIsRejectedWithoutPersisting(SuspensionReason reason) {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(reason).build());

        assertThatThrownBy(() -> manualPayments.register("tenant-1", request("REF-2", null), ADMIN))
                .isInstanceOf(SubscriptionAdminSuspendedException.class)
                .extracting(e -> ((BillingException) e).getCode()).isEqualTo("SUBSCRIPTION_ADMIN_SUSPENDED");
        assertThat(payments.payments).isEmpty();
        assertThat(events.events).isEmpty();
        assertThat(subscriptions.saves).isZero();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
    }

    @Test
    void manualPaymentOnPastDueKeepsCalendarAndActivates() {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.PAST_DUE)
                .pastDueReason(PastDueReason.PERIOD_UNPAID).currentPeriodEnd(NOW.minusDays(3))
                .graceEndsAt(NOW.plusDays(4)).build());

        ManualPaymentResult result = manualPayments.register("tenant-1", request("REF-3", null), ADMIN);

        assertThat(result.created()).isTrue();
        assertThat(result.payment().applied()).isTrue();
        assertThat(result.subscription().status()).isEqualTo("ACTIVE");
        assertThat(s.getCurrentPeriodStart()).isEqualTo(NOW.minusDays(3));
        assertThat(s.getCurrentPeriodEnd().toLocalDate()).isEqualTo(NOW.minusDays(3).plusMonths(1).toLocalDate());
        assertThat(s.getBillingAnchorDay()).isEqualTo(NOW.minusDays(3).getDayOfMonth());
        assertThat(result.payment().amount()).isEqualTo("25000.00");
    }

    @Test
    void manualPaymentWithExplicitPeriodEnd() {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.CANCELED).build());

        manualPayments.register("tenant-1", request("REF-5", NOW.plusMonths(3)), ADMIN);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.getCurrentPeriodEnd()).isEqualTo(NOW.plusMonths(3));
    }

    @Test
    void doubleSubmitWithSameReferenceReturnsTheOriginalPaymentWithoutExtendingAgain() {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(3)).build());

        ManualPaymentResult first = manualPayments.register("tenant-1", request("REF-4", null), ADMIN);
        OffsetDateTime endAfterFirst = s.getCurrentPeriodEnd();
        ManualPaymentResult second = manualPayments.register("tenant-1", request(" REF-4 ", null), ADMIN);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
        assertThat(payments.payments).hasSize(1);
        assertThat(s.getCurrentPeriodEnd()).isEqualTo(endAfterFirst);
        assertThat(events.types()).containsExactly(SubscriptionEventType.PAYMENT_APPLIED);
    }

    @Test
    void sameReferenceWithDifferentAmountIsAConflict() {
        store(subscription().planId(pro.getId()).build());
        manualPayments.register("tenant-1", request("REF-6", null), ADMIN);

        assertThatThrownBy(() -> manualPayments.register("tenant-1", new ManualPaymentRequest(new BigDecimal("1"),
                "ARS", NOW, "REF-6", null, null, "x"), ADMIN)).isInstanceOf(DuplicatePaymentException.class);
        assertThat(payments.payments).hasSize(1);
    }

    @Test
    void invalidAmountsCurrencyOrMissingReferenceAreRejected() {
        store(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> manualPayments.register("tenant-1", new ManualPaymentRequest(BigDecimal.ZERO, "ARS",
                null, "R", null, null, "x"), ADMIN)).isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> manualPayments.register("tenant-1", new ManualPaymentRequest(BigDecimal.TEN, "USD",
                null, "R", null, null, "x"), ADMIN)).isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> manualPayments.register("tenant-1", new ManualPaymentRequest(
                new BigDecimal("10.005"), "ARS", null, "R", null, null, "x"), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> manualPayments.register("tenant-1", new ManualPaymentRequest(BigDecimal.TEN, "ARS",
                null, " ", null, null, "x"), ADMIN)).isInstanceOf(InvalidBillingRequestException.class);
        assertThat(payments.payments).isEmpty();
    }

    @Test
    void trailingZerosBeyondTheScaleAreAccepted() {
        store(subscription().planId(pro.getId()).build());

        ManualPaymentResult result = manualPayments.register("tenant-1", new ManualPaymentRequest(
                new BigDecimal("100.500"), "ARS", null, "R-7", null, null, "x"), ADMIN);

        assertThat(result.payment().amount()).isEqualTo("100.50");
    }

    private Subscription store(Subscription s) {
        subscriptions.put(s);
        return s;
    }

    private static ManualPaymentRequest request(String reference, OffsetDateTime periodEnd) {
        return new ManualPaymentRequest(new BigDecimal("25000"), "ARS", NOW, reference, periodEnd, "Transferencia",
                "Pago por transferencia");
    }
}
