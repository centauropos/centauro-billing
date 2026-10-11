package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.CheckoutRequest;
import com.centauro.billing.application.dto.CheckoutView;
import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewayChargeStatus;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.PaymentStatus;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.model.WebhookEvent;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.WebhookNotification;
import com.centauro.billing.support.GatewayHarness;
import com.centauro.billing.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookUseCaseTest {

    private static final String PREAPPROVAL = WebhookUseCase.TOPIC_PREAPPROVAL;
    private static final String PAYMENT = WebhookUseCase.TOPIC_AUTHORIZED_PAYMENT;
    private static final BigDecimal PRICE = new BigDecimal("59900");

    private GatewayHarness h;
    private Plan pro;

    @BeforeEach
    void setUp() {
        h = new GatewayHarness();
        pro = h.plans.put(plan("PROFESIONAL", true, PRICE));
        h.plans.put(plan("INICIAL", true, new BigDecimal("14900")));
    }

    private UUID checkout(String planCode) {
        CheckoutView view = h.checkoutUseCase.start("tenant-1", new CheckoutRequest(planCode, null),
                Actor.tenantUser("owner"));
        return view.checkoutId();
    }

    private boolean notify(String notificationId, String topic, String resourceId) {
        return h.webhookUseCase.receive(new WebhookNotification(GatewayName.MERCADOPAGO, notificationId,
                "req-" + notificationId, topic, "updated", resourceId, false, Map.of("id", notificationId)));
    }

    private WebhookEvent event(String notificationId) {
        return h.webhookEvents.all().stream().filter(e -> notificationId.equals(e.getNotificationId()))
                .findFirst().orElseThrow();
    }

    private Subscription linkedTrial() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = checkout("PROFESIONAL");
        h.preapproval("pre-1", GatewaySubscriptionStatus.AUTHORIZED, checkoutId, PRICE, NOW);
        notify("n-auth", PREAPPROVAL, "pre-1");
        return s;
    }

    @Test
    void authorizedPreapprovalLinksTheDebitButDoesNotActivate() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = checkout("PROFESIONAL");
        h.preapproval("pre-1", GatewaySubscriptionStatus.AUTHORIZED, checkoutId, PRICE, NOW);

        assertThat(notify("n-1", PREAPPROVAL, "pre-1")).isTrue();

        assertThat(s.getGatewaySubscriptionId()).isEqualTo("pre-1");
        assertThat(s.isGatewayAuthorized()).isTrue();
        assertThat(s.getPriceAmount()).isEqualByComparingTo(PRICE);
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(h.checkouts.findById(checkoutId).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.AUTHORIZED);
        assertThat(h.events.types()).containsExactly(SubscriptionEventType.PAYMENT_METHOD_AUTHORIZED);
        assertThat(event("n-1").getStatus()).isEqualTo(WebhookStatus.PROCESSED);
    }

    @Test
    void sameNotificationTwiceIsStoredAndProcessedOnce() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = checkout("PROFESIONAL");
        h.preapproval("pre-1", GatewaySubscriptionStatus.AUTHORIZED, checkoutId, PRICE, NOW);

        assertThat(notify("n-1", PREAPPROVAL, "pre-1")).isTrue();
        assertThat(notify("n-1", PREAPPROVAL, "pre-1")).isFalse();

        assertThat(h.webhookEvents.all()).hasSize(1);
        assertThat(h.gateway.subscriptionLookups).containsExactly("pre-1");
        assertThat(s.isGatewayAuthorized()).isTrue();
    }

    @Test
    void approvedChargeIsAppliedOnceEvenIfNotifiedAgainWithAnotherId() {
        Subscription s = linkedTrial();
        h.charge("ap-1", "pre-1", GatewayChargeStatus.APPROVED, PRICE);

        notify("n-2", PAYMENT, "ap-1");
        notify("n-3", PAYMENT, "ap-1");

        assertThat(h.payments.payments.values()).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(p.applied()).isTrue();
            assertThat(p.gatewayPaymentId()).isEqualTo("ap-1");
            assertThat(p.amount()).isEqualByComparingTo(PRICE);
        });
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(h.events.types()).filteredOn(t -> t == SubscriptionEventType.PAYMENT_APPLIED).hasSize(1);
        assertThat(event("n-2").getStatus()).isEqualTo(WebhookStatus.PROCESSED);
        assertThat(event("n-3").getStatus()).isEqualTo(WebhookStatus.IGNORED);
    }

    @Test
    void chargeArrivingBeforeTheAuthorizationLinksFirstAndThenApplies() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = checkout("PROFESIONAL");
        h.preapproval("pre-1", GatewaySubscriptionStatus.AUTHORIZED, checkoutId, PRICE, NOW);
        h.charge("ap-1", "pre-1", GatewayChargeStatus.APPROVED, PRICE);

        notify("n-1", PAYMENT, "ap-1");

        assertThat(s.getGatewaySubscriptionId()).isEqualTo("pre-1");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(h.events.types()).containsSubsequence(SubscriptionEventType.PAYMENT_METHOD_AUTHORIZED,
                SubscriptionEventType.PAYMENT_APPLIED);
    }

    @Test
    void rejectedChargeIsRecordedWithNoticeAndNoStatusChange() {
        Subscription s = linkedTrial();
        h.charge("ap-1", "pre-1", GatewayChargeStatus.REJECTED, PRICE);

        notify("n-2", PAYMENT, "ap-1");
        notify("n-3", PAYMENT, "ap-1");

        assertThat(h.payments.payments.values()).singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo(PaymentStatus.REJECTED));
        assertThat(h.events.types()).filteredOn(t -> t == SubscriptionEventType.PAYMENT_REJECTED).hasSize(1);
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(TestFixtures.assembler(h.checkouts, h.payments, h.gatewayOperations).tenantView(s, pro, NOW)
                .notice().code()).isEqualTo("PAYMENT_REJECTED");
    }

    @Test
    void rejectedThenApprovedRetryOfTheSameChargeIsApplied() {
        Subscription s = linkedTrial();
        h.charge("ap-1", "pre-1", GatewayChargeStatus.REJECTED, PRICE);
        notify("n-2", PAYMENT, "ap-1");
        h.charge("ap-1", "pre-1", GatewayChargeStatus.APPROVED, PRICE);

        notify("n-3", PAYMENT, "ap-1");

        assertThat(h.payments.payments.values()).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(p.applied()).isTrue();
        });
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void approvedChargeBelowThePriceIsRecordedWithoutApplying() {
        Subscription s = linkedTrial();
        h.charge("ap-1", "pre-1", GatewayChargeStatus.APPROVED, new BigDecimal("100"));

        notify("n-2", PAYMENT, "ap-1");

        Payment payment = h.payments.payments.values().iterator().next();
        assertThat(payment.applied()).isFalse();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(h.events.types()).contains(SubscriptionEventType.PAYMENT_AMOUNT_MISMATCH);
    }

    @Test
    void approvedChargeInAnotherCurrencyIsRecordedWithoutApplying() {
        Subscription s = linkedTrial();
        h.charge("ap-1", "pre-1", GatewayChargeStatus.APPROVED, PRICE, "USD");

        notify("n-2", PAYMENT, "ap-1");

        Payment payment = h.payments.payments.values().iterator().next();
        assertThat(payment.applied()).isFalse();
        assertThat(payment.currency()).isEqualTo("ARS");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(h.events.types()).contains(SubscriptionEventType.PAYMENT_AMOUNT_MISMATCH);
    }

    @Test
    void approvedChargeWithAdminSuspensionIsStoredButNotApplied() {
        Subscription s = h.store(subscription().planId(pro.getId()).status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY)
                .gatewaySubscriptionId("pre-9").gatewayStatus("authorized").build());
        h.charge("ap-1", "pre-9", GatewayChargeStatus.APPROVED, new BigDecimal("25000"));

        notify("n-1", PAYMENT, "ap-1");

        assertThat(h.payments.payments.values()).singleElement()
                .satisfies(p -> assertThat(p.applied()).isFalse());
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(h.events.types()).containsExactly(SubscriptionEventType.PAYMENT_RECORDED);
    }

    @Test
    void outdatedPreapprovalNotificationIsIgnored() {
        Subscription s = h.store(subscription().planId(pro.getId()).gatewaySubscriptionId("pre-9")
                .gatewayStatus("authorized").gatewayLastModified(NOW).build());
        h.preapproval("pre-9", GatewaySubscriptionStatus.PAUSED, null, PRICE, NOW.minusMinutes(5));

        notify("n-1", PREAPPROVAL, "pre-9");

        assertThat(event("n-1").getStatus()).isEqualTo(WebhookStatus.IGNORED);
        assertThat(s.getGatewayStatus()).isEqualTo("authorized");

        h.preapproval("pre-9", GatewaySubscriptionStatus.PAUSED, null, PRICE, NOW.plusMinutes(1));
        notify("n-2", PREAPPROVAL, "pre-9");

        assertThat(s.getGatewayStatus()).isEqualTo("paused");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(h.events.types()).containsExactly(SubscriptionEventType.GATEWAY_PAUSED);
    }

    @Test
    void cancelledCurrentDebitKeepsTheSubscriptionAndRecordsTheEvent() {
        Subscription s = h.store(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-9").gatewayStatus("authorized")
                .gatewayLastModified(NOW.minusDays(1)).build());
        h.preapproval("pre-9", GatewaySubscriptionStatus.CANCELLED, null, PRICE, NOW);

        notify("n-1", PREAPPROVAL, "pre-9");

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.isGatewayAuthorized()).isFalse();
        assertThat(h.events.types()).containsExactly(SubscriptionEventType.GATEWAY_CANCELLED);
    }

    @Test
    void newAuthorizationReplacesAndCancelsThePreviousDebit() {
        Subscription s = h.store(subscription().planId(pro.getId()).status(SubscriptionStatus.PAST_DUE)
                .pastDueReason(PastDueReason.PERIOD_UNPAID).currentPeriodEnd(NOW.minusDays(1))
                .graceEndsAt(NOW.plusDays(6)).gatewaySubscriptionId("pre-0").gatewayStatus("authorized").build());
        UUID checkoutId = checkout("PROFESIONAL");
        h.preapproval("pre-1", GatewaySubscriptionStatus.AUTHORIZED, checkoutId, PRICE, NOW);

        notify("n-1", PREAPPROVAL, "pre-1");

        assertThat(s.getGatewaySubscriptionId()).isEqualTo("pre-1");
        assertThat(h.gateway.canceled).containsExactly("pre-0");
    }

    @Test
    void authorizingASupersededCheckoutCancelsItsPreapproval() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        UUID first = checkout("PROFESIONAL");
        checkout("INICIAL");
        h.preapproval("pre-1", GatewaySubscriptionStatus.AUTHORIZED, first, PRICE, NOW);

        notify("n-1", PREAPPROVAL, "pre-1");

        assertThat(s.hasGatewaySubscription()).isFalse();
        assertThat(h.gateway.canceled).containsExactly("pre-1", "pre-1");
        assertThat(event("n-1").getStatus()).isEqualTo(WebhookStatus.IGNORED);
    }

    @Test
    void cancelledPreapprovalOfAPendingCheckoutCancelsTheCheckout() {
        h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = checkout("PROFESIONAL");
        h.preapproval("pre-1", GatewaySubscriptionStatus.CANCELLED, checkoutId, PRICE, NOW);

        notify("n-1", PREAPPROVAL, "pre-1");

        assertThat(h.checkouts.findById(checkoutId).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.CANCELLED);
    }

    @Test
    void processingErrorLeavesTheEventFailedWithBackoffWithoutPropagating() {
        h.store(subscription().planId(pro.getId()).build());
        h.gateway.readFailure = new IllegalStateException("MP caído");

        assertThat(notify("n-1", PREAPPROVAL, "pre-1")).isTrue();

        WebhookEvent stored = event("n-1");
        assertThat(stored.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getNextAttemptAt()).isAtSameInstantAs(NOW.plusMinutes(2));
        assertThat(stored.getLastError()).contains("MP caído");
    }

    @Test
    void unknownTopicIsIgnoredWithoutCallingTheGateway() {
        notify("n-1", "payment", "123");

        assertThat(event("n-1").getStatus()).isEqualTo(WebhookStatus.IGNORED);
        assertThat(h.gateway.subscriptionLookups).isEmpty();
        assertThat(h.gateway.chargeLookups).isEmpty();
    }

    @Test
    void chargeOfAnUnknownPreapprovalIsIgnored() {
        h.charge("ap-1", "pre-x", GatewayChargeStatus.APPROVED, PRICE);
        h.preapproval("pre-x", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);

        notify("n-1", PAYMENT, "ap-1");

        assertThat(event("n-1").getStatus()).isEqualTo(WebhookStatus.IGNORED);
        assertThat(h.payments.payments).isEmpty();
    }
}
