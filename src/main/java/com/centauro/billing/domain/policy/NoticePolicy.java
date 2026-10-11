package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.AccessLevel;
import com.centauro.billing.domain.enums.NoticeCode;
import com.centauro.billing.domain.enums.NoticeSeverity;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.AccessDecision;
import com.centauro.billing.domain.model.Notice;
import com.centauro.billing.domain.model.Subscription;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class NoticePolicy {

    static final int TRIAL_ENDING_DAYS = 3;

    private final ZoneId zone;

    public NoticePolicy(ZoneId zone) {
        this.zone = zone;
    }

    /** Devuelve {@code null} cuando no hay nada que avisar (ACTIVE al día o tenant desconocido). */
    public Notice notice(Subscription subscription, AccessDecision decision, OffsetDateTime now) {
        return notice(subscription, decision, false, now);
    }

    /**
     * {@code lastPaymentRejected}: el último pago registrado es un cobro rechazado de la pasarela. Se avisa
     * mientras haya acceso completo; READ_ONLY y BLOCKED tienen prioridad.
     */
    public Notice notice(Subscription subscription, AccessDecision decision, boolean lastPaymentRejected,
                         OffsetDateTime now) {
        if (subscription == null || !decision.known()) {
            return null;
        }
        if (decision.accessLevel() == AccessLevel.BLOCKED) {
            return new Notice(NoticeCode.BLOCKED, NoticeSeverity.CRITICAL, null);
        }
        if (decision.accessLevel() == AccessLevel.READ_ONLY) {
            return new Notice(NoticeCode.READ_ONLY, NoticeSeverity.CRITICAL, null);
        }
        SubscriptionStatus effective = decision.effectiveStatus();
        if (lastPaymentRejected) {
            Integer days = effective == SubscriptionStatus.PAST_DUE
                    ? daysUntil(decision.effectiveGraceEndsAt(), now) : null;
            return new Notice(NoticeCode.PAYMENT_REJECTED, NoticeSeverity.WARNING, days);
        }
        if (effective == SubscriptionStatus.PAST_DUE) {
            return new Notice(NoticeCode.PAYMENT_OVERDUE, NoticeSeverity.WARNING,
                    daysUntil(decision.effectiveGraceEndsAt(), now));
        }
        if (subscription.isCancelAtPeriodEnd()) {
            OffsetDateTime end = effective == SubscriptionStatus.TRIALING
                    ? subscription.getTrialEndsAt() : subscription.getCurrentPeriodEnd();
            return new Notice(NoticeCode.CANCELLATION_SCHEDULED, NoticeSeverity.INFO, daysUntil(end, now));
        }
        if (effective == SubscriptionStatus.TRIALING) {
            Integer days = daysUntil(subscription.getTrialEndsAt(), now);
            if (days != null && days <= TRIAL_ENDING_DAYS) {
                return new Notice(NoticeCode.TRIAL_ENDING, NoticeSeverity.WARNING, days);
            }
            return new Notice(NoticeCode.TRIAL_ACTIVE, NoticeSeverity.INFO, days);
        }
        return null;
    }

    /**
     * Días calendario AR entre hoy y el último día con acceso; nunca negativo. Los vencimientos son
     * exclusivos, así que el último día con acceso es el del instante inmediatamente anterior.
     */
    public Integer daysUntil(OffsetDateTime target, OffsetDateTime now) {
        if (target == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(now.atZoneSameInstant(zone).toLocalDate(),
                target.minusNanos(1).atZoneSameInstant(zone).toLocalDate());
        return (int) Math.max(0, days);
    }
}
