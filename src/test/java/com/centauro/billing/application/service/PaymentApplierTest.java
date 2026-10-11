package com.centauro.billing.application.service;

import com.centauro.billing.application.dto.ManualPaymentRequest;
import com.centauro.billing.application.dto.ManualPaymentResult;
import com.centauro.billing.application.usecase.ManualPaymentUseCase;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.DuplicatePaymentException;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.InMemoryEventRepository;
import com.centauro.billing.support.InMemoryPaymentRepository;
import com.centauro.billing.support.InMemoryPlanRepository;
import com.centauro.billing.support.InMemorySubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
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
        pro = plans.put(plan("PRO", false, new BigDecimal("25000")));
        applier = new PaymentApplier(subscriptions, payments, events);
        SubscriptionViewAssembler assembler = new SubscriptionViewAssembler(new AccessPolicy(TERMS),
                new NoticePolicy(AR), new AllowedActionsPolicy(), TERMS);
        manualPayments = new ManualPaymentUseCase(subscriptions, payments, plans, applier,
                new PeriodCalculator(AR), assembler, clockAt(NOW));
    }

    @Test
    void appliedFlagMakesApplicationIdempotent() {
        Subscription s = store(subscription().planId(pro.getId()).build());
        Payment payment = payments.save(Payment.manual(s, new BigDecimal("25000"), NOW, NOW, NOW.plusMonths(1),
                "REF-1", null, "admin", NOW));

        PaymentApplier.Result first = applier.apply(s, payment, GatewayName.MANUAL, ADMIN, "x", NOW);
        PaymentApplier.Result second = applier.apply(s, payment, GatewayName.MANUAL, ADMIN, "x", NOW);

        assertThat(first.applied()).isTrue();
        assertThat(second.applied()).isFalse();
        assertThat(payments.payments.get(payment.id()).applied()).isTrue();
        assertThat(events.types()).containsExactly(SubscriptionEventType.PAYMENT_APPLIED);
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void adminSuspensionRecordsPaymentWithoutReactivating() {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY).build());

        ManualPaymentResult result = manualPayments.register("tenant-1", request("REF-2", null), ADMIN);

        assertThat(result.payment().applied()).isFalse();
        assertThat(result.subscription().status()).isEqualTo("SUSPENDED");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(events.types()).containsExactly(SubscriptionEventType.PAYMENT_RECORDED);
    }

    @Test
    void manualPaymentOnPastDueKeepsCalendarAndActivates() {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.PAST_DUE)
                .pastDueReason(PastDueReason.PERIOD_UNPAID).currentPeriodEnd(NOW.minusDays(3))
                .graceEndsAt(NOW.plusDays(4)).build());

        ManualPaymentResult result = manualPayments.register("tenant-1", request("REF-3", null), ADMIN);

        assertThat(result.payment().applied()).isTrue();
        assertThat(result.subscription().status()).isEqualTo("ACTIVE");
        assertThat(s.getCurrentPeriodStart()).isEqualTo(NOW.minusDays(3));
        assertThat(s.getCurrentPeriodEnd().toLocalDate()).isEqualTo(NOW.minusDays(3).plusMonths(1).toLocalDate());
        assertThat(result.payment().amount()).isEqualTo("25000.00");
    }

    @Test
    void manualPaymentWithExplicitPeriodEnd() {
        Subscription s = store(subscription().planId(pro.getId()).status(SubscriptionStatus.CANCELED).build());

        manualPayments.register("tenant-1", request(null, NOW.plusMonths(3)), ADMIN);

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.getCurrentPeriodEnd()).isEqualTo(NOW.plusMonths(3));
    }

    @Test
    void duplicateReferenceIsRejected() {
        store(subscription().planId(pro.getId()).build());
        manualPayments.register("tenant-1", request("REF-4", null), ADMIN);

        assertThatThrownBy(() -> manualPayments.register("tenant-1", request("REF-4", null), ADMIN))
                .isInstanceOf(DuplicatePaymentException.class);
        assertThat(payments.payments).hasSize(1);
    }

    @Test
    void nonPositiveAmountOrForeignCurrencyIsRejected() {
        store(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> manualPayments.register("tenant-1", new ManualPaymentRequest(BigDecimal.ZERO, "ARS",
                null, null, null, null, "x"), ADMIN)).isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> manualPayments.register("tenant-1", new ManualPaymentRequest(BigDecimal.TEN, "USD",
                null, null, null, null, "x"), ADMIN)).isInstanceOf(InvalidBillingRequestException.class);
    }

    private Subscription store(Subscription s) {
        subscriptions.put(s);
        return s;
    }

    private static ManualPaymentRequest request(String reference, java.time.OffsetDateTime periodEnd) {
        return new ManualPaymentRequest(new BigDecimal("25000"), "ARS", NOW, reference, periodEnd, "Transferencia",
                "Pago por transferencia");
    }
}
