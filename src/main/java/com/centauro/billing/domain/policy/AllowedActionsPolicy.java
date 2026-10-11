package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.AllowedAction;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.AccessDecision;
import com.centauro.billing.domain.model.Subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * Acciones que el tenant puede ejecutar, calculadas sobre el estado efectivo de {@link AccessPolicy}
 * (un job atrasado no habilita acciones que ya no corresponden). CHECKOUT llega con la pasarela (PR1b).
 */
public final class AllowedActionsPolicy {

    public List<AllowedAction> allowedActions(Subscription subscription, AccessDecision decision) {
        List<AllowedAction> actions = new ArrayList<>();
        if (subscription == null || decision == null || !decision.known()) {
            return actions;
        }
        SubscriptionStatus status = decision.effectiveStatus();
        SuspensionReason suspension = decision.effectiveSuspensionReason();
        boolean adminSuspended = status == SubscriptionStatus.SUSPENDED && suspension != SuspensionReason.NON_PAYMENT;
        if (status != SubscriptionStatus.CANCELED && !adminSuspended) {
            actions.add(AllowedAction.CHANGE_PLAN);
        }
        boolean schedulable = (status == SubscriptionStatus.TRIALING || status == SubscriptionStatus.ACTIVE)
                && !subscription.isCancelAtPeriodEnd();
        boolean immediate = status == SubscriptionStatus.PAST_DUE
                || (status == SubscriptionStatus.SUSPENDED && suspension == SuspensionReason.NON_PAYMENT);
        if (schedulable || immediate) {
            actions.add(AllowedAction.CANCEL);
        }
        return actions;
    }
}
