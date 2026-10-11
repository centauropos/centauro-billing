package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.AllowedAction;
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
}
