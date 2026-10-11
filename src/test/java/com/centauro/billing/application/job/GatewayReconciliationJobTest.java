package com.centauro.billing.application.job;

import com.centauro.billing.application.dto.CheckoutRequest;
import com.centauro.billing.domain.enums.GatewayChargeStatus;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.GatewayHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayReconciliationJobTest {

    private static final BigDecimal PRICE = new BigDecimal("59900");

    private GatewayHarness h;
    private TestJobLock lock;
    private Plan pro;

    @BeforeEach
    void setUp() {
        h = new GatewayHarness();
        lock = new TestJobLock();
        pro = h.plans.put(plan("PROFESIONAL", true, PRICE));
        h.plans.put(plan("INICIAL", true, new BigDecimal("14900")));
    }

    private GatewayReconciliationJob job(GatewayHarness harness) {
        return new GatewayReconciliationJob(harness.subscriptions, harness.checkouts, harness.sync,
                harness.gatewayOperations, lock, clockAt(NOW), 1);
    }

    private UUID checkout(String planCode) {
        return h.checkoutUseCase.start("tenant-1", new CheckoutRequest(planCode, null), Actor.tenantUser("owner"))
                .checkoutId();
    }

    @Test
    void appliesChargesWhoseWebhookWasLost() {
        Subscription s = h.store(subscription().planId(pro.getId()).priceAmount(PRICE)
                .gatewaySubscriptionId("pre-1").gatewayStatus("authorized").build());
        h.preapproval("pre-1", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);
        h.gateway.chargesBySubscription.put("pre-1",
                List.of(h.charge("ap-1", "pre-1", GatewayChargeStatus.APPROVED, PRICE)));

        assertThat(job(h).run()).isTrue();
        job(h).run();

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(h.payments.payments.values()).singleElement().satisfies(p -> assertThat(p.applied()).isTrue());
        assertThat(h.gateway.canceled).isEmpty();
        assertThat(h.gateway.amountUpdates).isEmpty();
        assertThat(lock.keys).containsOnly(GatewayReconciliationJob.LOCK_KEY);
    }

    @Test
    void cancelsALiveDebitOfALocallyCanceledSubscription() {
        h.store(subscription().status(SubscriptionStatus.CANCELED).gatewaySubscriptionId("pre-2")
                .gatewayStatus("authorized").build());
        h.preapproval("pre-2", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);

        job(h).run();

        assertThat(h.gateway.canceled).containsExactly("pre-2");
        assertThat(h.events.types()).contains(SubscriptionEventType.GATEWAY_RECONCILED);
    }

    @Test
    void correctsTheDebitAmountWhenItDiffersFromThePrice() {
        h.store(subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(10)).priceAmount(PRICE)
                .gatewaySubscriptionId("pre-3").gatewayStatus("authorized").build());
        h.preapproval("pre-3", GatewaySubscriptionStatus.AUTHORIZED, null, new BigDecimal("14900"), NOW);

        job(h).run();

        assertThat(h.gateway.amountUpdates).singleElement().asString().startsWith("pre-3:59900");
        assertThat(h.events.types()).contains(SubscriptionEventType.GATEWAY_AMOUNT_MISMATCH);
    }

    @Test
    void cancelsAnAuthorizedPreapprovalOfASupersededCheckout() {
        h.store(subscription().planId(pro.getId()).build());
        UUID first = checkout("PROFESIONAL");
        checkout("INICIAL");
        String orphan = h.checkouts.findById(first).orElseThrow().getGatewayPreapprovalId();
        h.preapproval(orphan, GatewaySubscriptionStatus.AUTHORIZED, first, PRICE, NOW);
        h.gateway.canceled.clear();

        job(h).run();

        assertThat(h.gateway.canceled).containsExactly(orphan);
    }

    @Test
    void linksAnExpiredCheckoutAuthorizedAfterItsWebhookWasLost() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        UUID id = checkout("PROFESIONAL");
        GatewayCheckout expired = h.checkouts.findById(id).orElseThrow();
        expired.markExpired(NOW);
        h.checkouts.save(expired);
        h.preapproval(expired.getGatewayPreapprovalId(), GatewaySubscriptionStatus.AUTHORIZED, id, PRICE, NOW);

        job(h).run();

        assertThat(s.getGatewaySubscriptionId()).isEqualTo(expired.getGatewayPreapprovalId());
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void withoutGatewayDoesNotRun() {
        GatewayHarness noGateway = new GatewayHarness(false, GatewayHarness.BACK_URL);

        assertThat(job(noGateway).run()).isFalse();
        assertThat(lock.keys).isEmpty();
    }
}
