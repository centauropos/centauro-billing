package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.AuthorizeCheckoutRequest;
import com.centauro.billing.application.dto.CheckoutRequest;
import com.centauro.billing.application.dto.CheckoutView;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.CheckoutInProgressException;
import com.centauro.billing.domain.exception.CheckoutNotAllowedException;
import com.centauro.billing.domain.exception.CheckoutNotFoundException;
import com.centauro.billing.domain.exception.CheckoutPlanRequiresContactException;
import com.centauro.billing.domain.exception.GatewayRejectedException;
import com.centauro.billing.domain.exception.GatewayTimeoutException;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanRequiresContactException;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.application.service.CheckoutTransactions;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.support.GatewayHarness;
import com.centauro.billing.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.customPlan;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutUseCaseTest {

    private static final Actor OWNER = Actor.tenantUser("owner", "7");
    private static final AuthorizeCheckoutRequest TOKEN = new AuthorizeCheckoutRequest("card-tok-1");

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

    private CheckoutView prepare(String planCode) {
        return h.checkoutUseCase.prepare("tenant-1", new CheckoutRequest(planCode, null), OWNER);
    }

    private CheckoutView authorize(UUID checkoutId) {
        return h.checkoutUseCase.authorize("tenant-1", checkoutId, TOKEN, OWNER);
    }

    @Test
    void prepareCreatesPendingCheckoutWithoutCallingTheGateway() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());

        CheckoutView view = prepare("PROFESIONAL");

        assertThat(view.reused()).isFalse();
        assertThat(view.initPoint()).isNull();
        assertThat(view.amount()).isEqualTo("59900.00");
        assertThat(view.currency()).isEqualTo("ARS");
        assertThat(view.planCode()).isEqualTo("PROFESIONAL");
        assertThat(view.payerEmail()).isEqualTo("owner@test.com");
        assertThat(view.startDate()).isEqualTo(s.getTrialEndsAt());
        assertThat(h.gateway.created).isEmpty();
        GatewayCheckout stored = h.checkouts.findById(view.checkoutId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(stored.getGatewayPreapprovalId()).isNull();
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void prepareIgnoresClientPayerEmailAndUsesBillingEmail() {
        h.store(subscription().planId(pro.getId()).billingEmail("billing@tenant.com").build());

        CheckoutView view = h.checkoutUseCase.prepare("tenant-1",
                new CheckoutRequest("PROFESIONAL", "other@client.com"), OWNER);

        assertThat(view.payerEmail()).isEqualTo("billing@tenant.com");
        assertThat(h.checkouts.findById(view.checkoutId()).orElseThrow().getPayerEmail())
                .isEqualTo("billing@tenant.com");
    }

    @Test
    void pendingCheckoutForSamePlanAndAmountIsReusedWithoutGateway() {
        h.store(subscription().planId(pro.getId()).build());

        CheckoutView first = prepare("PROFESIONAL");
        CheckoutView second = prepare("PROFESIONAL");

        assertThat(second.reused()).isTrue();
        assertThat(second.checkoutId()).isEqualTo(first.checkoutId());
        assertThat(h.gateway.created).isEmpty();
        assertThat(h.checkouts.all()).hasSize(1);
    }

    @Test
    void prepareForAnotherPlanSupersedesThePendingOne() {
        h.store(subscription().planId(pro.getId()).build());

        CheckoutView first = prepare("PROFESIONAL");
        CheckoutView second = prepare("INICIAL");

        assertThat(second.reused()).isFalse();
        assertThat(h.checkouts.findById(first.checkoutId()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.SUPERSEDED);
        assertThat(h.checkouts.findById(second.checkoutId()).orElseThrow().getPlanId()).isEqualTo(inicial.getId());
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void authorizeCreatesAuthorizedPreapprovalWithCardToken() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        CheckoutView prepared = prepare("PROFESIONAL");

        CheckoutView view = authorize(prepared.checkoutId());

        assertThat(view.reused()).isFalse();
        assertThat(view.initPoint()).isNull();
        assertThat(h.gateway.created).hasSize(1);
        RecurringCheckoutRequest sent = h.gateway.created.get(0);
        assertThat(sent.checkoutId()).isEqualTo(prepared.checkoutId());
        assertThat(sent.cardTokenId()).isEqualTo("card-tok-1");
        assertThat(sent.payerEmail()).isEqualTo("owner@test.com");
        assertThat(sent.amount()).isEqualByComparingTo("59900");
        assertThat(sent.startDate()).isEqualTo(s.getTrialEndsAt());
        assertThat(sent.backUrl()).isEqualTo(GatewayHarness.BACK_URL + "?checkout=" + prepared.checkoutId());
        GatewayCheckout stored = h.checkouts.findById(view.checkoutId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(stored.getGatewayPreapprovalId()).isEqualTo("pre-1");
        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void authorizeIsIdempotentWhenPreapprovalAlreadyAttached() {
        h.store(subscription().planId(pro.getId()).build());
        CheckoutView prepared = prepare("PROFESIONAL");
        CheckoutView first = authorize(prepared.checkoutId());

        CheckoutView second = authorize(prepared.checkoutId());

        assertThat(second.reused()).isTrue();
        assertThat(second.checkoutId()).isEqualTo(first.checkoutId());
        assertThat(h.gateway.created).hasSize(1);
    }

    @Test
    void authorizeRaceWhileFirstIsInFlightIsAConflict() {
        h.store(subscription().planId(pro.getId()).build());
        CheckoutView prepared = prepare("PROFESIONAL");
        // Simula el claim del primer authorize (updatedAt > createdAt, sin preapproval).
        GatewayCheckout inFlight = h.checkouts.findById(prepared.checkoutId()).orElseThrow();
        inFlight.markAuthorizeAttempted(NOW.plusSeconds(1));
        h.checkouts.save(inFlight);

        assertThatThrownBy(() -> authorize(prepared.checkoutId())).isInstanceOf(CheckoutInProgressException.class)
                .extracting("code").isEqualTo("CHECKOUT_IN_PROGRESS");
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void authorizeClaimStaysFirmAfterThirtySeconds() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        // Claim con updatedAt hace 10 min (>30 s): la ventana vieja lo habría liberado; el claim firme no.
        GatewayCheckout inFlight = h.checkouts.save(GatewayCheckout.create(s, pro.getId(), new BigDecimal("59900"),
                "owner@test.com", null, NOW.plusHours(24), "owner", NOW.minusHours(1)));
        inFlight.markAuthorizeAttempted(NOW.minusMinutes(10));
        h.checkouts.save(inFlight);

        assertThat(CheckoutTransactions.isAuthorizeInProgress(inFlight)).isTrue();
        assertThatThrownBy(() -> authorize(inFlight.getId())).isInstanceOf(CheckoutInProgressException.class)
                .extracting("code").isEqualTo("CHECKOUT_IN_PROGRESS");
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void prepareSamePlanWhileAuthorizeInFlightIsAConflict() {
        h.store(subscription().planId(pro.getId()).build());
        CheckoutView prepared = prepare("PROFESIONAL");
        GatewayCheckout inFlight = h.checkouts.findById(prepared.checkoutId()).orElseThrow();
        inFlight.markAuthorizeAttempted(NOW.plusSeconds(1));
        h.checkouts.save(inFlight);

        assertThatThrownBy(() -> prepare("PROFESIONAL")).isInstanceOf(CheckoutInProgressException.class)
                .extracting("code").isEqualTo("CHECKOUT_IN_PROGRESS");
        assertThat(h.checkouts.all()).hasSize(1);
    }

    @Test
    void authorizeRejectedWhenSubscriptionIsAdminSuspended() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = prepare("PROFESIONAL").checkoutId();
        s.suspendByAdmin(SuspensionReason.ADMIN_READ_ONLY, Actor.platformAdmin("admin"), "bloqueo", NOW);
        h.subscriptions.put(s);

        assertThatThrownBy(() -> authorize(checkoutId)).isInstanceOf(CheckoutNotAllowedException.class);
        assertThat(h.gateway.created).isEmpty();
        assertThat(h.checkouts.findById(checkoutId).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.PENDING);
    }

    @Test
    void authorizeOfAnotherTenantCheckoutIsNotFound() {
        h.store(subscription().planId(pro.getId()).build());
        CheckoutView prepared = prepare("PROFESIONAL");

        assertThatThrownBy(() -> h.checkoutUseCase.authorize("otro-tenant", prepared.checkoutId(), TOKEN, OWNER))
                .isInstanceOf(CheckoutNotFoundException.class);
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void authorizeOfExpiredOrNonPendingCheckoutIsNotAllowed() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        GatewayCheckout expired = h.checkouts.save(GatewayCheckout.create(s, pro.getId(), new BigDecimal("59900"),
                "owner@test.com", null, NOW.minusMinutes(1), "owner", NOW.minusHours(25)));
        GatewayCheckout failed = GatewayCheckout.create(s, pro.getId(), new BigDecimal("59900"),
                "owner@test.com", null, NOW.plusHours(24), "owner", NOW);
        failed.markFailed(NOW);
        h.checkouts.save(failed);

        assertThatThrownBy(() -> authorize(expired.getId())).isInstanceOf(CheckoutNotAllowedException.class);
        assertThatThrownBy(() -> authorize(failed.getId())).isInstanceOf(CheckoutNotAllowedException.class);
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void prepareDoesNotRequireGatewayOrBackUrl() {
        GatewayHarness noGateway = new GatewayHarness(false, GatewayHarness.BACK_URL);
        noGateway.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        noGateway.store(subscription().build());
        GatewayHarness noBackUrl = new GatewayHarness(true, " ");
        noBackUrl.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        noBackUrl.store(subscription().build());

        assertThat(noGateway.checkoutUseCase.prepare("tenant-1", new CheckoutRequest("PROFESIONAL", null), OWNER)
                .initPoint()).isNull();
        assertThat(noBackUrl.checkoutUseCase.prepare("tenant-1", new CheckoutRequest("PROFESIONAL", null), OWNER)
                .checkoutId()).isNotNull();
    }

    @Test
    void authorizeWithoutGatewayOrBackUrlIsBadGateway() {
        GatewayHarness noGateway = new GatewayHarness(false, GatewayHarness.BACK_URL);
        noGateway.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        noGateway.store(subscription().build());
        UUID checkoutId = noGateway.checkoutUseCase.prepare("tenant-1", new CheckoutRequest("PROFESIONAL", null), OWNER)
                .checkoutId();
        GatewayHarness noBackUrl = new GatewayHarness(true, " ");
        noBackUrl.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        noBackUrl.store(subscription().build());
        UUID otherId = noBackUrl.checkoutUseCase.prepare("tenant-1", new CheckoutRequest("PROFESIONAL", null), OWNER)
                .checkoutId();

        assertThatThrownBy(() -> noGateway.checkoutUseCase.authorize("tenant-1", checkoutId, TOKEN, OWNER))
                .isInstanceOf(GatewayUnavailableException.class);
        assertThatThrownBy(() -> noBackUrl.checkoutUseCase.authorize("tenant-1", otherId, TOKEN, OWNER))
                .isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void customPlanRequiresContact() {
        h.store(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> prepare("EMPRESA")).isInstanceOf(CheckoutPlanRequiresContactException.class)
                .isInstanceOf(PlanRequiresContactException.class).extracting("code").isEqualTo("PLAN_REQUIRES_CONTACT");
        assertThat(h.checkouts.all()).isEmpty();
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void nonVisiblePlanIsNotOffered() {
        h.store(subscription().planId(pro.getId()).build());

        assertThatThrownBy(() -> prepare("INTERNO")).isInstanceOf(PlanNotFoundException.class);
        assertThat(h.checkouts.all()).isEmpty();
    }

    @Test
    void gatewayRejectionMarksFailedAndUsesGenericMessage() {
        h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = prepare("PROFESIONAL").checkoutId();
        h.gateway.failure = new GatewayRejectedException("Invalid card_token_id card_tok_fake_9f3a");

        assertThatThrownBy(() -> authorize(checkoutId)).isInstanceOf(GatewayRejectedException.class)
                .hasMessage("No se pudo iniciar el pago con Mercado Pago");
        assertThat(h.checkouts.findById(checkoutId).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.FAILED);
    }

    @Test
    void gatewayTimeoutLeavesPendingAndAllowsRetryWithSameCheckout() {
        h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = prepare("PROFESIONAL").checkoutId();
        h.gateway.failure = new GatewayTimeoutException("timeout");

        assertThatThrownBy(() -> authorize(checkoutId)).isInstanceOf(GatewayTimeoutException.class);

        GatewayCheckout afterTimeout = h.checkouts.findById(checkoutId).orElseThrow();
        assertThat(afterTimeout.getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(afterTimeout.getGatewayPreapprovalId()).isNull();
        assertThat(CheckoutTransactions.isAuthorizeInProgress(afterTimeout)).isFalse();

        h.gateway.failure = null;
        CheckoutView retried = authorize(checkoutId);
        assertThat(retried.checkoutId()).isEqualTo(checkoutId);
        assertThat(h.gateway.created).hasSize(1);
        assertThat(h.checkouts.findById(checkoutId).orElseThrow().getGatewayPreapprovalId()).isEqualTo("pre-1");
    }

    @Test
    void gatewayUnavailableLeavesPendingWithoutFailing() {
        h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = prepare("PROFESIONAL").checkoutId();
        h.gateway.failure = new GatewayUnavailableException("502");

        assertThatThrownBy(() -> authorize(checkoutId)).isInstanceOf(GatewayUnavailableException.class);
        assertThat(h.checkouts.findById(checkoutId).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(CheckoutTransactions.isAuthorizeInProgress(
                h.checkouts.findById(checkoutId).orElseThrow())).isFalse();
    }

    @Test
    void unexpectedGatewayErrorIsReportedAsUnavailableAndLeavesPending() {
        h.store(subscription().planId(pro.getId()).build());
        UUID checkoutId = prepare("PROFESIONAL").checkoutId();
        h.gateway.failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> authorize(checkoutId)).isInstanceOf(GatewayUnavailableException.class);
        assertThat(h.checkouts.findById(checkoutId).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.PENDING);
    }

    @Test
    void redactForLogMasksCardTokensAndEmails() {
        assertThat(CheckoutUseCase.redactForLog("card_token_id=tok_secret for owner@test.com"))
                .contains("card_token_id=***")
                .contains("***")
                .doesNotContain("tok_secret")
                .doesNotContain("owner@test.com");
    }

    @Test
    void notAllowedWithAuthorizedDebitInForceOrAdminSuspension() {
        h.store(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).gatewaySubscriptionId("pre-0").gatewayStatus("authorized").build());
        GatewayHarness suspended = new GatewayHarness();
        suspended.plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        suspended.store(subscription().status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY).build());

        assertThatThrownBy(() -> prepare("PROFESIONAL")).isInstanceOf(CheckoutNotAllowedException.class);
        assertThatThrownBy(() -> suspended.checkoutUseCase.prepare("tenant-1",
                new CheckoutRequest("PROFESIONAL", null), OWNER)).isInstanceOf(CheckoutNotAllowedException.class);
        assertThat(h.gateway.created).isEmpty();
    }

    @Test
    void activeSubscriptionStartsChargingWhenThePaidPeriodEnds() {
        Subscription s = h.store(subscription().planId(pro.getId()).status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).build());
        UUID checkoutId = prepare("PROFESIONAL").checkoutId();

        authorize(checkoutId);

        assertThat(h.gateway.created.get(0).startDate()).isEqualTo(s.getCurrentPeriodEnd());
    }

    @Test
    void authorizeForAnotherPlanAfterFirstAuthorizeCancelsPreviousPreapproval() {
        h.store(subscription().planId(pro.getId()).build());
        UUID firstId = prepare("PROFESIONAL").checkoutId();
        authorize(firstId);

        CheckoutView secondPrepare = prepare("INICIAL");
        authorize(secondPrepare.checkoutId());

        assertThat(h.checkouts.findById(firstId).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.SUPERSEDED);
        assertThat(h.gateway.canceled).containsExactly("pre-1");
    }

    @Test
    void tenantViewShowsThePendingCheckoutWithoutRequiringInitPoint() {
        Subscription s = h.store(subscription().planId(pro.getId()).build());
        CheckoutView view = prepare("PROFESIONAL");

        TenantSubscriptionView tenantView = TestFixtures.assembler(h.checkouts, h.payments, h.gatewayOperations)
                .tenantView(s, pro, NOW);

        assertThat(tenantView.pendingCheckout()).isNotNull();
        assertThat(tenantView.pendingCheckout().checkoutId()).isEqualTo(view.checkoutId());
        assertThat(tenantView.pendingCheckout().initPoint()).isNull();
        assertThat(tenantView.allowedActions()).contains("CHECKOUT");
        assertThat(tenantView.hasPaymentMethod()).isFalse();
    }
}
