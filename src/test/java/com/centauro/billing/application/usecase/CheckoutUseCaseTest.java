package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.CheckoutRequest;
import com.centauro.billing.application.dto.CheckoutView;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.CheckoutInProgressException;
import com.centauro.billing.domain.exception.CheckoutNotAllowedException;
import com.centauro.billing.domain.exception.CheckoutPlanRequiresContactException;
import com.centauro.billing.domain.exception.GatewayRejectedException;
import com.centauro.billing.domain.exception.GatewayTimeoutException;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanRequiresContactException;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.support.GatewayHarness;
import com.centauro.billing.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.customPlan;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutUseCaseTest {

    private static final Actor OWNER = Actor.tenantUser("owner", "7");

    private GatewayHarness h;
    private Plan pro;
    private Plan inicial;

    @BeforeEach
    void setUp() {
        h = new GatewayHarness();
        pro = h.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        inicial = h.plans.put(plan("INICIAL", true, new BigDecimal("14900")));
        h.plans.put(plan("INTERNO", false, new BigDecimal("1")));
        h.plans.put(customPlan("EMPRESA", true));
    }

    private CheckoutView start(String planCode) {
        return h.checkoutUseCase.start("tenant-1", new CheckoutRequest(planCode, null), OWNER);
    }

    @Test
    void createsPreapprovalStartingWhenTheTrialEnds() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());

        CheckoutView view = start("PROFESIONAL");

        assertThat(view.reused()).isFalse();
        assertThat(view.initPoint()).isEqualTo("https://mp.test/checkout/pre-1");
        assertThat(view.amount()).isEqualTo("59900.00");
        assertThat(view.currency()).isEqualTo("ARS");
        assertThat(h.gateway.created).hasSize(1);
        RecurringCheckoutRequest sent = h.gateway.created.get(0);
        assertThat(sent.checkoutId()).isEqualTo(view.checkoutId());
        assertThat(sent.payerEmail()).isEqualTo("owner@test.com");
        assertThat(sent.amount()).isEqualByComparingTo("59900");
        assertThat(sent.startDate()).isEqualTo(s.getTrialEndsAt());
        assertThat(sent.backUrl()).isEqualTo(GatewayHarness.BACK_URL + "?checkout=" + view.checkoutId());
        GatewayCheckout stored = h.checkouts.findById(view.checkoutId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(stored.getGatewayPreapprovalId()).isEqualTo("pre-1");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void pendingCheckoutForSamePlanAndAmountIsReused() {
        h.store(subscription().planId(pro.getId()).build());

        CheckoutView first = start("PROFESIONAL");
        CheckoutView second = start("PROFESIONAL");

        assertThat(second.reused()).isTrue();
        assertThat(second.checkoutId()).isEqualTo(first.checkoutId());
        assertThat(second.initPoint()).isEqualTo(first.initPoint());
        assertThat(h.gateway.created).hasSize(1);
    }

    @Test
    void checkoutForAnotherPlanSupersedesThePendingOneAndCancelsItsPreapproval() {
        h.store(subscription().planId(pro.getId()).build());

        CheckoutView first = start("PROFESIONAL");
        CheckoutView second = start("INICIAL");

        assertThat(second.reused()).isFalse();
        assertThat(h.checkouts.findById(first.checkoutId()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.SUPERSEDED);
        assertThat(h.gateway.canceled).containsExactly("pre-1");
        assertThat(h.checkouts.findById(second.checkoutId()).orElseThrow().getPlanId()).isEqualTo(inicial.getId());
    }

    @Test
    void doubleClickWhileTheFirstCheckoutIsBeingCreatedIsAConflict() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        // El primer pedido ya guardó el checkout PENDING pero todavía no volvió de MP (sin init_point).
        GatewayCheckout inFlight = h.checkouts.save(GatewayCheckout.create(s, pro.getId(), new BigDecimal("59900"),
                "owner@test.com", null, NOW.plusHours(24), "owner", NOW.minusSeconds(10)));

        assertThatThrownBy(() -> start("PROFESIONAL")).isInstanceOf(CheckoutInProgressException.class)
                .extracting("code").isEqualTo("CHECKOUT_IN_PROGRESS");

        assertThat(h.checkouts.findById(inFlight.getId()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.PENDING);
        assertThat(h.gateway.created).isEmpty();
        assertThat(h.gateway.canceled).isEmpty();
    }

    @Test
    void anOldCheckoutWithoutInitPointOrForAnotherPlanIsNotInProgress() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        GatewayCheckout stale = h.checkouts.save(GatewayCheckout.create(s, pro.getId(), new BigDecimal("59900"),
                "owner@test.com", null, NOW.plusHours(24), "owner", NOW.minusSeconds(31)));

        start("PROFESIONAL");

        assertThat(h.checkouts.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.SUPERSEDED);

        GatewayHarness other = new GatewayHarness();
        other.plans.put(pro);
        other.plans.put(inicial);
        Subscription t = other.store(subscription().planId(pro.getId()).build());
        other.checkouts.save(GatewayCheckout.create(t, inicial.getId(), new BigDecimal("14900"), "owner@test.com",
                null, NOW.plusHours(24), "owner", NOW.minusSeconds(5)));

        assertThat(other.checkoutUseCase.start("tenant-1", new CheckoutRequest("PROFESIONAL", null), OWNER)
                .reused()).isFalse();
    }

    @Test
    void gatewayErrorsReachTheTenantWithAGenericMessage() {
        h.store(subscription().planId(pro.getId()).build());
        h.gateway.failure = new GatewayRejectedException("payer_email inválido: owner@test.com (status 400)");

        assertThatThrownBy(() -> start("PROFESIONAL")).isInstanceOf(GatewayRejectedException.class)
                .hasMessage("No se pudo iniciar el pago con Mercado Pago");
    }

    @Test
    void customPlanRequiresContact() {
        h.store(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> start("EMPRESA")).isInstanceOf(CheckoutPlanRequiresContactException.class)
                .isInstanceOf(PlanRequiresContactException.class).extracting("code").isEqualTo("PLAN_REQUIRES_CONTACT");
        assertThat(h.checkouts.all()).isEmpty();
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void nonVisiblePlanIsNotOffered() {
        h.store(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> start("INTERNO")).isInstanceOf(PlanNotFoundException.class);
        assertThat(h.checkouts.all()).isEmpty();
    }

    @Test
    void withoutGatewayOrBackUrlIsBadGateway() {
        GatewayHarness noGateway = new GatewayHarness(false, GatewayHarness.BACK_URL);
        noGateway.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        noGateway.store(subscription().build());
        GatewayHarness noBackUrl = new GatewayHarness(true, " ");
        noBackUrl.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        noBackUrl.store(subscription().build());

        assertThatThrownBy(() -> noGateway.checkoutUseCase.start("tenant-1",
                new CheckoutRequest("PROFESIONAL", null), OWNER)).isInstanceOf(GatewayUnavailableException.class);
        assertThatThrownBy(() -> noBackUrl.checkoutUseCase.start("tenant-1",
                new CheckoutRequest("PROFESIONAL", null), OWNER)).isInstanceOf(GatewayUnavailableException.class);
        assertThat(noGateway.checkouts.all()).isEmpty();
        assertThat(noBackUrl.checkouts.all()).isEmpty();
    }

    @Test
    void gatewayTimeoutLeavesTheCheckoutFailedAndPropagates() {
        h.store(subscription().planId(pro.getId()).build());
        h.gateway.failure = new GatewayTimeoutException("timeout");

        assertThatThrownBy(() -> start("PROFESIONAL")).isInstanceOf(GatewayTimeoutException.class);

        assertThat(h.checkouts.all()).singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(CheckoutStatus.FAILED));
    }

    @Test
    void unexpectedGatewayErrorIsReportedAsUnavailable() {
        h.store(subscription().planId(pro.getId()).build());
        h.gateway.failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> start("PROFESIONAL")).isInstanceOf(GatewayUnavailableException.class);
        assertThat(h.checkouts.all()).singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(CheckoutStatus.FAILED));
    }

    @Test
    void notAllowedWithAuthorizedDebitInForceOrAdminSuspension() {
        h.store(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-0").gatewayStatus("authorized").build());
        GatewayHarness suspended = new GatewayHarness();
        suspended.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        suspended.store(subscription().status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY).build());

        assertThatThrownBy(() -> start("PROFESIONAL")).isInstanceOf(CheckoutNotAllowedException.class);
        assertThatThrownBy(() -> suspended.checkoutUseCase.start("tenant-1",
                new CheckoutRequest("PROFESIONAL", null), OWNER)).isInstanceOf(CheckoutNotAllowedException.class);
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void activeSubscriptionStartsChargingWhenThePaidPeriodEnds() {
        Subscription s = h.store(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).build());

        start("PROFESIONAL");

        assertThat(h.gateway.created.get(0).startDate()).isEqualTo(s.getCurrentPeriodEnd());
    }

    @Test
    void tenantViewShowsThePendingCheckout() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        CheckoutView view = start("PROFESIONAL");

        TenantSubscriptionView tenantView = TestFixtures.assembler(h.checkouts, h.payments, h.gatewayOperations)
                .tenantView(s, pro, NOW);

        assertThat(tenantView.pendingCheckout()).isNotNull();
        assertThat(tenantView.pendingCheckout().initPoint()).isEqualTo(view.initPoint());
        assertThat(tenantView.allowedActions()).contains("CHECKOUT");
        assertThat(tenantView.hasPaymentMethod()).isFalse();
    }
}
