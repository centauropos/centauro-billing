package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.AccessLevel;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.AccessDecision;
import com.centauro.billing.domain.model.Subscription;

import java.time.OffsetDateTime;

/**
 * Evalúa el acceso contra el reloj: si un job viene atrasado, el estado efectivo ya refleja el vencimiento.
 */
public final class AccessPolicy {

    private final BillingTerms terms;

    public AccessPolicy(BillingTerms terms) {
        this.terms = terms;
    }

    public AccessDecision evaluate(Subscription subscription, OffsetDateTime now) {
        if (subscription == null) {
            return AccessDecision.unknown(now);
        }
        SubscriptionStatus effective = subscription.getStatus();
        SuspensionReason suspension = subscription.getSuspensionReason();
        OffsetDateTime grace = subscription.getGraceEndsAt();

        if (effective == SubscriptionStatus.TRIALING && subscription.isTrialExpired(now)) {
            if (subscription.isCancelAtPeriodEnd()) {
                effective = SubscriptionStatus.CANCELED;
            } else {
                effective = SubscriptionStatus.PAST_DUE;
                grace = subscription.getTrialEndsAt().plusDays(terms.trialGraceDays());
            }
        } else if (effective == SubscriptionStatus.ACTIVE && subscription.isPeriodExpired(now)) {
            if (subscription.isCancelAtPeriodEnd()) {
                effective = SubscriptionStatus.CANCELED;
            } else {
                effective = SubscriptionStatus.PAST_DUE;
                grace = subscription.getCurrentPeriodEnd().plusDays(terms.graceDays());
            }
        }

        if (effective == SubscriptionStatus.PAST_DUE && grace != null && !grace.isAfter(now)) {
            effective = SubscriptionStatus.SUSPENDED;
            suspension = SuspensionReason.NON_PAYMENT;
        }

        AccessLevel level = switch (effective) {
            case TRIALING, ACTIVE, PAST_DUE -> AccessLevel.FULL;
            case SUSPENDED -> suspension == SuspensionReason.ADMIN_BLOCK ? AccessLevel.BLOCKED : AccessLevel.READ_ONLY;
            case CANCELED -> AccessLevel.READ_ONLY;
        };
        SuspensionReason effectiveSuspension = effective == SubscriptionStatus.SUSPENDED ? suspension : null;
        OffsetDateTime effectiveGrace = effective == SubscriptionStatus.PAST_DUE
                || effective == SubscriptionStatus.SUSPENDED ? grace : null;
        return new AccessDecision(true, subscription.getStatus(), effective, effectiveSuspension, level,
                effectiveGrace, now);
    }
}
