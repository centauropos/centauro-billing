package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.Subscription;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Calendario de facturación en la zona de negocio. Todos los vencimientos ({@code *_ends_at},
 * {@code current_period_end}) son exclusivos: el acceso vale mientras {@code now < vencimiento}.
 */
public final class PeriodCalculator {

    private final ZoneId zone;

    public PeriodCalculator(ZoneId zone) {
        this.zone = zone;
    }

    public record Period(OffsetDateTime start, OffsetDateTime end, int anchorDay) {
    }

    /**
     * Período que cubre un pago aprobado. Pagar tarde no regala días: con ACTIVE o PAST_DUE por período
     * impago, el período nuevo arranca donde terminó el anterior y respeta el día ancla.
     * Con un fin explícito, el ancla pasa a ser el día de ese fin.
     */
    public Period nextPeriod(Subscription subscription, OffsetDateTime now, OffsetDateTime manualPeriodEnd) {
        OffsetDateTime start = startFor(subscription, now);
        if (manualPeriodEnd != null) {
            return new Period(start, manualPeriodEnd, dayOf(manualPeriodEnd));
        }
        int anchor = anchorDayFor(subscription, start);
        return new Period(start, plusOneMonth(start, anchor), anchor);
    }

    OffsetDateTime startFor(Subscription subscription, OffsetDateTime now) {
        if (continuesCurrentPeriod(subscription)) {
            return subscription.getCurrentPeriodEnd();
        }
        if (subscription.getStatus() == SubscriptionStatus.TRIALING && subscription.getTrialEndsAt() != null
                && subscription.getTrialEndsAt().isAfter(now)) {
            return subscription.getTrialEndsAt();
        }
        return now;
    }

    /**
     * Si el período continúa el actual, el ancla guardada; si no hay, el día de {@code current_period_start}.
     * Un calendario nuevo (trial, reactivación tras baja o suspensión) se ancla en su propio inicio.
     */
    int anchorDayFor(Subscription subscription, OffsetDateTime start) {
        if (continuesCurrentPeriod(subscription)) {
            if (subscription.getBillingAnchorDay() != null) {
                return subscription.getBillingAnchorDay();
            }
            if (subscription.getCurrentPeriodStart() != null) {
                return dayOf(subscription.getCurrentPeriodStart());
            }
        }
        return dayOf(start);
    }

    private static boolean continuesCurrentPeriod(Subscription subscription) {
        SubscriptionStatus status = subscription.getStatus();
        boolean periodUnpaid = status == SubscriptionStatus.PAST_DUE
                && subscription.getPastDueReason() == PastDueReason.PERIOD_UNPAID;
        return (status == SubscriptionStatus.ACTIVE || periodUnpaid) && subscription.getCurrentPeriodEnd() != null;
    }

    /** Un mes calendario después, en el día ancla limitado al largo del mes (ancla 31: 28/02, 31/03, 30/04). */
    public OffsetDateTime plusOneMonth(OffsetDateTime start, int anchorDay) {
        if (anchorDay < 1 || anchorDay > 31) {
            throw new IllegalArgumentException("Día ancla fuera de rango: " + anchorDay);
        }
        ZonedDateTime local = start.atZoneSameInstant(zone);
        YearMonth next = YearMonth.from(local).plusMonths(1);
        int day = Math.min(anchorDay, next.lengthOfMonth());
        return next.atDay(day).atTime(local.toLocalTime()).atZone(zone).toOffsetDateTime();
    }

    public OffsetDateTime plusOneMonth(OffsetDateTime start) {
        return plusOneMonth(start, dayOf(start));
    }

    /** Día del mes en la zona de negocio. */
    public int dayOf(OffsetDateTime instant) {
        return instant.atZoneSameInstant(zone).getDayOfMonth();
    }

    /**
     * Vencimiento exclusivo que cubre {@code days} días calendario AR completos después del día de
     * {@code from}: el inicio (00:00) del día siguiente al último día incluido.
     */
    public OffsetDateTime startOfDayAfter(OffsetDateTime from, int days) {
        return from.atZoneSameInstant(zone).toLocalDate().plusDays(days + 1L)
                .atStartOfDay(zone).toOffsetDateTime();
    }

    /**
     * Corre {@code days} días calendario AR un vencimiento exclusivo ya vigente. Si {@code end} cae a
     * las 00:00 AR, el día de {@code end} no tiene acceso y no cuenta; si no, se cubre completo.
     */
    public OffsetDateTime extendExclusiveEnd(OffsetDateTime end, int days) {
        ZonedDateTime local = end.atZoneSameInstant(zone);
        if (local.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return local.toLocalDate().plusDays(days).atStartOfDay(zone).toOffsetDateTime();
        }
        return startOfDayAfter(end, days);
    }
}
