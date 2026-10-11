package com.centauro.billing.application.job;

import com.centauro.billing.application.dto.CheckoutRequest;
import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.WebhookNotification;
import com.centauro.billing.support.GatewayHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutExpirationJobTest {

    private GatewayHarness h;
    private TestJobLock lock;

    @BeforeEach
    void setUp() {
        h = new GatewayHarness();
        h.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        lock = new TestJobLock();
    }

    private UUID checkoutFor(String tenantId) {
        h.store(subscription().tenantId(tenantId).build());
        return h.checkoutUseCase.start(tenantId, new CheckoutRequest("PROFESIONAL", null), Actor.tenantUser("owner"))
                .checkoutId();
    }

    private CheckoutExpirationJob jobAt(OffsetDateTime now, int batchSize) {
        return new CheckoutExpirationJob(h.checkouts, new CheckoutExpirationProcessor(h.checkouts),
                h.gatewayOperations, h.sync, lock, clockAt(now), batchSize);
    }

    @Test
    void preapprovalAuthorizedJustBeforeExpiringIsLinkedInsteadOfCanceled() {
        UUID id = checkoutFor("t1");
        String preapprovalId = checkout(id).getGatewayPreapprovalId();
        // El pagador autorizó, pero el webhook todavía no llegó cuando corre el job.
        h.preapproval(preapprovalId, GatewaySubscriptionStatus.AUTHORIZED, id, new BigDecimal("59900"), NOW);

        jobAt(NOW.plusHours(25), 100).run();

        assertThat(h.gateway.canceled).isEmpty();
        Subscription s = h.subscriptions.findByTenantId("t1").orElseThrow();
        assertThat(s.getGatewaySubscriptionId()).isEqualTo(preapprovalId);
        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.AUTHORIZED);

        // El webhook llega después: el preapproval ya es el vigente y no cambia nada.
        h.webhookUseCase.receive(new WebhookNotification(GatewayName.MERCADOPAGO, null, "req-late",
                "subscription_preapproval", "updated", preapprovalId, true, Map.of()));
        assertThat(h.gateway.canceled).isEmpty();
        assertThat(h.subscriptions.findByTenantId("t1").orElseThrow().getGatewaySubscriptionId())
                .isEqualTo(preapprovalId);
    }

    @Test
    void whenTheWebhookWinsTheRaceTheJobLeavesTheCheckoutAlone() {
        UUID id = checkoutFor("t1");
        String preapprovalId = checkout(id).getGatewayPreapprovalId();
        h.preapproval(preapprovalId, GatewaySubscriptionStatus.AUTHORIZED, id, new BigDecimal("59900"), NOW);
        h.webhookUseCase.receive(new WebhookNotification(GatewayName.MERCADOPAGO, null, "req-1",
                "subscription_preapproval", "updated", preapprovalId, true, Map.of()));

        jobAt(NOW.plusHours(25), 100).run();

        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.AUTHORIZED);
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void neverCancelsThePreapprovalLinkedToTheSubscription() {
        UUID id = checkoutFor("t1");
        String preapprovalId = checkout(id).getGatewayPreapprovalId();
        Subscription s = h.subscriptions.findByTenantId("t1").orElseThrow();
        s.linkGatewaySubscription(GatewayName.MERCADOPAGO, preapprovalId, NOW, null, s.getPlanId(),
                new BigDecimal("59900"), null, Actor.job("test"), NOW);
        h.preapproval(preapprovalId, GatewaySubscriptionStatus.PAUSED, id, new BigDecimal("59900"), NOW);

        jobAt(NOW.plusHours(25), 100).run();

        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void ifThePreapprovalCannotBeQueriedNothingIsCanceled() {
        UUID id = checkoutFor("t1");
        h.gateway.readFailure = new IllegalStateException("MP caído");

        jobAt(NOW.plusHours(25), 100).run();

        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(h.gateway.canceled).isEmpty();
    }

    private GatewayCheckout checkout(UUID id) {
        return h.checkouts.findById(id).orElseThrow();
    }

    @Test
    void expiresDueCheckoutsAcrossBatchesAndCancelsTheirPreapprovals() {
        UUID first = checkoutFor("t1");
        UUID second = checkoutFor("t2");

        assertThat(jobAt(NOW.plusHours(25), 1).run()).isTrue();

        assertThat(checkout(first).getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(checkout(second).getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(h.gateway.canceled).containsExactlyInAnyOrder(checkout(first).getGatewayPreapprovalId(),
                checkout(second).getGatewayPreapprovalId());
        assertThat(lock.keys).containsExactly(CheckoutExpirationJob.LOCK_KEY);
    }

    @Test
    void checkoutsStillInForceAreNotTouched() {
        UUID id = checkoutFor("t1");

        jobAt(NOW.plusHours(1), 100).run();

        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void rowLockedByAnotherTransactionIsSkipped() {
        UUID id = checkoutFor("t1");
        h.checkouts.lockedByOthers.add(id);

        jobAt(NOW.plusHours(25), 100).run();

        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void cancelFailureInTheGatewayDoesNotUndoTheExpiration() {
        UUID id = checkoutFor("t1");
        h.gateway.failure = new IllegalStateException("MP caído");

        assertThat(jobAt(NOW.plusHours(25), 100).run()).isTrue();

        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
    }

    @Test
    void withoutLockDoesNothing() {
        UUID id = checkoutFor("t1");
        lock.available = false;

        assertThat(jobAt(NOW.plusHours(25), 100).run()).isFalse();

        assertThat(checkout(id).getStatus()).isEqualTo(CheckoutStatus.PENDING);
    }
}
