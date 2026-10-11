package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.AllowedAction;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.AccessDecision;
import com.centauro.billing.domain.model.Subscription;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class AllowedActionsPolicyTest {

    private final AccessPolicy accessPolicy = new AccessPolicy(TERMS);
    private final AllowedActionsPolicy policy = new AllowedActionsPolicy();

    private List<AllowedAction> actions(Subscription s) {
        AccessDecision decision = accessPolicy.evaluate(s, NOW);
        return policy.allowedActions(s, decision);
    }

    @Test
    void activeWithScheduledCancellationWhosePeriodEndedIsEffectivelyCanceled() {
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.minusHours(1))
                .cancelAtPeriodEnd(true).build();

        assertThat(actions(s)).isEmpty();
    }

    @Test
    void activeWhoseGraceAlreadyEndedAllowsImmediateCancel() {
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.minusDays(10)).build();

        assertThat(actions(s)).containsExactly(AllowedAction.CHANGE_PLAN, AllowedAction.CANCEL);
    }

    @Test
    void trialingWithScheduledCancellationKeepsOnlyChangePlanWhileInTrial() {
        Subscription s = subscription().cancelAtPeriodEnd(true).build();

        assertThat(actions(s)).containsExactly(AllowedAction.CHANGE_PLAN);
    }

    @Test
    void adminSuspensionAllowsNothing() {
        Subscription s = subscription().status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_READ_ONLY).build();

        assertThat(actions(s)).isEmpty();
    }

    @Test
    void unknownTenantHasNoActions() {
        assertThat(policy.allowedActions(null, AccessDecision.unknown(NOW))).isEmpty();
    }

    private List<AllowedAction> withGateway(Subscription s) {
        return policy.allowedActions(s, accessPolicy.evaluate(s, NOW), true);
    }

    @Test
    void checkoutIsOfferedOnlyWithGatewayConfigured() {
        Subscription s = subscription().build();

        assertThat(actions(s)).doesNotContain(AllowedAction.CHECKOUT);
        assertThat(withGateway(s)).containsExactly(AllowedAction.CHECKOUT, AllowedAction.CHANGE_PLAN,
                AllowedAction.CANCEL);
    }

    @Test
    void checkoutIsNotOfferedWithAnAuthorizedDebitInForce() {
        Subscription active = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(10))
                .gatewaySubscriptionId("pre-1").gatewayStatus("authorized").build();
        Subscription paused = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(10))
                .gatewaySubscriptionId("pre-1").gatewayStatus("paused").build();
        Subscription cancelScheduled = subscription().status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(10)).cancelAtPeriodEnd(true)
                .gatewaySubscriptionId("pre-1").gatewayStatus("authorized").build();

        assertThat(withGateway(active)).doesNotContain(AllowedAction.CHECKOUT);
        assertThat(withGateway(paused)).contains(AllowedAction.CHECKOUT);
        assertThat(withGateway(cancelScheduled)).contains(AllowedAction.CHECKOUT);
    }

    @Test
    void checkoutIsAllowedPastDueEvenWithAuthorizedDebitButNeverWithAdminSuspension() {
        Subscription pastDue = subscription().status(SubscriptionStatus.PAST_DUE)
                .pastDueReason(PastDueReason.PERIOD_UNPAID)
                .currentPeriodEnd(NOW.minusDays(1)).graceEndsAt(NOW.plusDays(6))
                .gatewaySubscriptionId("pre-1").gatewayStatus("authorized").build();
        Subscription admin = subscription().status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_BLOCK).build();
        Subscription nonPayment = subscription().status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.NON_PAYMENT).build();

        assertThat(withGateway(pastDue)).contains(AllowedAction.CHECKOUT);
        assertThat(withGateway(admin)).isEmpty();
        assertThat(withGateway(nonPayment)).contains(AllowedAction.CHECKOUT);
    }
}
