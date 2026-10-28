package com.centauro.billing.application.job;

import com.centauro.billing.application.dto.AuthorizeCheckoutRequest;
import com.centauro.billing.application.dto.CheckoutRequest;
import com.centauro.billing.application.service.GatewaySettings;
import com.centauro.billing.domain.enums.GatewayChargeStatus;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.GatewayHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayReconciliationJobTest {

    private static final BigDecimal PRICE = new BigDecimal("59900");
    private static final Actor ADMIN = Actor.platformAdmin("admin", "42");

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
                harness.gatewayOperations, harness.settings, lock, clockAt(NOW), 1);
    }

    private GatewayReconciliationJob job(int maxItems, Duration maxDuration, Clock clock) {
        GatewaySettings limited = new GatewaySettings(Duration.ofHours(24), GatewayHarness.BACK_URL,
                GatewayHarness.MAX_ATTEMPTS, maxItems, maxDuration);
        return new GatewayReconciliationJob(h.subscriptions, h.checkouts, h.sync, h.gatewayOperations, limited, lock,
                clock, 1);
    }

    private Subscription linked(String preapprovalId, OffsetDateTime linkedAt) {
        Subscription s = h.store(subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(10))
                .planId(pro.getId()).priceAmount(PRICE).build());
        h.events.append(s.linkGatewaySubscription(GatewayName.MERCADOPAGO, preapprovalId, linkedAt, null,
                pro.getId(), PRICE, null, ADMIN, linkedAt));
        return s;
    }

    @Test
    void doesNotCancelADebitLinkedAfterTheCancellation() {
        Subscription s = h.store(subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(10))
                .planId(pro.getId()).priceAmount(PRICE).build());
        h.events.append(s.cancelImmediately(ADMIN, "baja", NOW.minusDays(2)));
        h.events.append(s.linkGatewaySubscription(GatewayName.MERCADOPAGO, "pre-new", NOW.minusDays(1), null,
                pro.getId(), PRICE, null, ADMIN, NOW.minusDays(1)));
        h.preapproval("pre-new", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);

        job(h).run();

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void cancelsADebitWhenTheCancellationCameAfterTheLink() {
        Subscription s = linked("pre-old", NOW.minusDays(3));
        h.events.append(s.cancelImmediately(ADMIN, "baja", NOW.minusDays(2)));
        h.preapproval("pre-old", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);

        job(h).run();

        assertThat(h.gateway.canceled).containsExactly("pre-old");
    }

    @Test
    void pausesTheDebitOfAnAdminSuspendedSubscription() {
        Subscription s = linked("pre-s", NOW.minusDays(5));
        h.events.append(s.suspendByAdmin(SuspensionReason.ADMIN_BLOCK, ADMIN, "fraude", NOW.minusDays(1)));
        h.preapproval("pre-s", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);

        job(h).run();

        assertThat(h.gateway.paused).containsExactly("pre-s");
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void resumesADebitPausedBeforeTheAdminReactivationButNotOnePausedLater() {
        Subscription s = linked("pre-r", NOW.minusDays(5));
        h.events.append(s.suspendByAdmin(SuspensionReason.ADMIN_BLOCK, ADMIN, "fraude", NOW.minusDays(3)));
        h.events.append(s.reactivate(NOW.plusDays(30), 10, ADMIN, "ok", NOW.minusDays(1)));
        h.preapproval("pre-r", GatewaySubscriptionStatus.PAUSED, null, PRICE, NOW.minusDays(2));

        job(h).run();

        assertThat(h.gateway.resumed).containsExactly("pre-r");

        GatewayHarness other = new GatewayHarness();
        h = other;
        pro = h.plans.put(plan("PROFESIONAL", true, PRICE));
        Subscription t = linked("pre-t", NOW.minusDays(5));
        h.events.append(t.suspendByAdmin(SuspensionReason.ADMIN_BLOCK, ADMIN, "fraude", NOW.minusDays(3)));
        h.events.append(t.reactivate(NOW.plusDays(30), 10, ADMIN, "ok", NOW.minusDays(2)));
        // El pagador pausó el débito en MP después de la reactivación: no se toca.
        h.preapproval("pre-t", GatewaySubscriptionStatus.PAUSED, null, PRICE, NOW.minusDays(1));

        job(h).run();

        assertThat(h.gateway.resumed).isEmpty();
    }

    @Test
    void stopsAfterTheConfiguredNumberOfItems() {
        linked("pre-a", NOW.minusDays(5));
        h.store(subscription().tenantId("tenant-2").status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).priceAmount(PRICE).gatewaySubscriptionId("pre-b")
                .gatewayStatus("authorized").build());
        h.preapproval("pre-a", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);
        h.preapproval("pre-b", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);

        assertThat(job(1, Duration.ofHours(1), clockAt(NOW)).run()).isTrue();

        assertThat(h.gateway.subscriptionLookups).hasSize(1);
    }

    @Test
    void stopsWhenTheTimeBudgetRunsOut() {
        linked("pre-a", NOW.minusDays(5));
        h.store(subscription().tenantId("tenant-2").status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).priceAmount(PRICE).gatewaySubscriptionId("pre-b")
                .gatewayStatus("authorized").build());
        h.preapproval("pre-a", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);
        h.preapproval("pre-b", GatewaySubscriptionStatus.AUTHORIZED, null, PRICE, NOW);

        // Cada lectura del reloj avanza un minuto: el presupuesto de 90 s alcanza para un solo elemento.
        assertThat(job(100, Duration.ofSeconds(90), new TickingClock(NOW.toInstant())).run()).isTrue();

        assertThat(h.gateway.subscriptionLookups).hasSize(1);
    }

    private static final class TickingClock extends Clock {
        private Instant current;

        TickingClock(Instant start) {
            current = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant now = current;
            current = current.plusSeconds(60);
            return now;
        }
    }

    private UUID checkout(String planCode) {
        Actor owner = Actor.tenantUser("owner");
        var prepared = h.checkoutUseCase.prepare("tenant-1", new CheckoutRequest(planCode, null), owner);
        return h.checkoutUseCase.authorize("tenant-1", prepared.checkoutId(),
                new AuthorizeCheckoutRequest("card-tok"), owner).checkoutId();
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
