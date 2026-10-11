package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.Subscription;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class PeriodCalculator {

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final ZoneId zone;

    public PeriodCalculator(ZoneId zone) {
        this.zone = zone;
    }

    public record Period(OffsetDateTime start, OffsetDateTime end) {
    }

    /**
     * Período que cubre un pago aprobado. Pagar tarde no regala días: con ACTIVE o PAST_DUE por período
     * impago, el período nuevo arranca donde terminó el anterior.
     */
    public Period nextPeriod(Subscription subscription, OffsetDateTime now, OffsetDateTime manualPeriodEnd) {
        OffsetDateTime start = startFor(subscription, now);
        OffsetDateTime end = manualPeriodEnd != null ? manualPeriodEnd : plusOneMonth(start);
        return new Period(start, end);
    }

    OffsetDateTime startFor(Subscription subscription, OffsetDateTime now) {
        SubscriptionStatus status = subscription.getStatus();
        boolean periodUnpaid = status == SubscriptionStatus.PAST_DUE
                && subscription.getPastDueReason() == PastDueReason.PERIOD_UNPAID;
        if ((status == SubscriptionStatus.ACTIVE || periodUnpaid) && subscription.getCurrentPeriodEnd() != null) {
            return subscription.getCurrentPeriodEnd();
        }
        if (status == SubscriptionStatus.TRIALING && subscription.getTrialEndsAt() != null
                && subscription.getTrialEndsAt().isAfter(now)) {
            return subscription.getTrialEndsAt();
        }
        return now;
    }

    /** Suma un mes calendario en la zona de negocio (31/01 pasa a 28/02 o 29/02). */
    public OffsetDateTime plusOneMonth(OffsetDateTime start) {
        return start.atZoneSameInstant(zone).plusMonths(1).toOffsetDateTime();
    }

    /** Fin del día AR (23:59:59) de {@code now + days}. */
    public OffsetDateTime endOfDayPlusDays(OffsetDateTime now, int days) {
        return now.atZoneSameInstant(zone).toLocalDate().plusDays(days)
                .atTime(END_OF_DAY).atZone(zone).toOffsetDateTime();
    }
}
