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
 * (un job atrasado no habilita acciones que ya no corresponden).
 */
public final class AllowedActionsPolicy {

    public List<AllowedAction> allowedActions(Subscription subscription, AccessDecision decision) {
        return allowedActions(subscription, decision, false);
    }

    /** {@code checkoutEnabled}: hay pasarela configurada; sin ella CHECKOUT nunca se ofrece. */
    public List<AllowedAction> allowedActions(Subscription subscription, AccessDecision decision,
                                              boolean checkoutEnabled) {
        List<AllowedAction> actions = new ArrayList<>();
        if (subscription == null || decision == null || !decision.known()) {
            return actions;
        }
        if (checkoutEnabled && canCheckout(subscription, decision)) {
            actions.add(AllowedAction.CHECKOUT);
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

    /**
     * Adherir el débito automático: no con suspensión administrativa (la levanta solo la plataforma) ni si
     * ya hay un débito autorizado vigente sin baja agendada (TRIALING o ACTIVE). En PAST_DUE, suspensión por
     * falta de pago o CANCELED siempre se puede, aunque haya un débito autorizado (cambiar de tarjeta).
     */
    public boolean canCheckout(Subscription subscription, AccessDecision decision) {
        if (subscription == null || decision == null || !decision.known()) {
            return false;
        }
        SubscriptionStatus status = decision.effectiveStatus();
        if (status == SubscriptionStatus.SUSPENDED
                && decision.effectiveSuspensionReason() != SuspensionReason.NON_PAYMENT) {
            return false;
        }
        boolean current = status == SubscriptionStatus.TRIALING || status == SubscriptionStatus.ACTIVE;
        return !(current && subscription.isGatewayAuthorized() && !subscription.isCancelAtPeriodEnd());
    }
}
