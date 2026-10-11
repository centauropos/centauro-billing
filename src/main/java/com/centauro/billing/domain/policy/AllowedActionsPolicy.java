package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.AllowedAction;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.Subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * Acciones que el tenant puede ejecutar, alineadas con lo que aceptan las transiciones de
 * {@link Subscription}. CHECKOUT se habilita cuando exista la pasarela (PR1b).
 */
public final class AllowedActionsPolicy {

    public List<AllowedAction> allowedActions(Subscription subscription) {
        List<AllowedAction> actions = new ArrayList<>();
        if (subscription == null) {
            return actions;
        }
        SubscriptionStatus status = subscription.getStatus();
        if (status != SubscriptionStatus.CANCELED && !subscription.isAdminSuspended()) {
            actions.add(AllowedAction.CHANGE_PLAN);
        }
        boolean schedulable = (status == SubscriptionStatus.TRIALING || status == SubscriptionStatus.ACTIVE)
                && !subscription.isCancelAtPeriodEnd();
        boolean immediate = status == SubscriptionStatus.PAST_DUE
                || (status == SubscriptionStatus.SUSPENDED
                && subscription.getSuspensionReason() == SuspensionReason.NON_PAYMENT);
        if (schedulable || immediate) {
            actions.add(AllowedAction.CANCEL);
        }
        return actions;
    }
}
