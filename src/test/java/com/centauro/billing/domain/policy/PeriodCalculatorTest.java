package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.vo.Actor;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.AR_OFFSET;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class PeriodCalculatorTest {

    private final PeriodCalculator calculator = new PeriodCalculator(AR);

    @Test
    void activeRenewalStartsAtCurrentPeriodEnd() {
        OffsetDateTime end = NOW.plusDays(3);
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(end).build();

        PeriodCalculator.Period period = calculator.nextPeriod(s, NOW, null);

        assertThat(period.start()).isEqualTo(end);
        assertThat(period.end()).isEqualTo(end.plusMonths(1));
    }

    @Test
    void latePaymentKeepsTheCalendar() {
        OffsetDateTime end = NOW.minusDays(4);
        Subscription s = subscription().status(SubscriptionStatus.PAST_DUE).pastDueReason(PastDueReason.PERIOD_UNPAID)
                .currentPeriodEnd(end).graceEndsAt(end.plusDays(7)).build();

        PeriodCalculator.Period period = calculator.nextPeriod(s, NOW, null);

        assertThat(period.start()).isEqualTo(end);
    }

    @Test
    void trialInTheFutureStartsAtTrialEnd() {
        OffsetDateTime trialEnd = NOW.plusDays(5);
        Subscription s = subscription().trialEndsAt(trialEnd).build();

        assertThat(calculator.nextPeriod(s, NOW, null).start()).isEqualTo(trialEnd);
    }

    @Test
    void expiredTrialSuspendedAndCanceledStartNow() {
        Subscription expiredTrial = subscription().status(SubscriptionStatus.PAST_DUE)
                .pastDueReason(PastDueReason.TRIAL_EXPIRED).trialEndsAt(NOW.minusDays(1))
                .graceEndsAt(NOW.plusDays(2)).build();
        Subscription suspended = subscription().status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.NON_PAYMENT).currentPeriodEnd(NOW.minusDays(20)).build();
        Subscription canceled = subscription().status(SubscriptionStatus.CANCELED).build();

        assertThat(calculator.nextPeriod(expiredTrial, NOW, null).start()).isEqualTo(NOW);
        assertThat(calculator.nextPeriod(suspended, NOW, null).start()).isEqualTo(NOW);
        assertThat(calculator.nextPeriod(canceled, NOW, null).start()).isEqualTo(NOW);
    }

    @Test
    void monthEndThirtyFirstGoesToLastDayOfShorterMonths() {
        OffsetDateTime jan31 = OffsetDateTime.of(2026, 1, 31, 23, 59, 59, 0, AR_OFFSET);
        OffsetDateTime mar31 = OffsetDateTime.of(2026, 3, 31, 12, 0, 0, 0, AR_OFFSET);
        OffsetDateTime jan31Leap = OffsetDateTime.of(2028, 1, 31, 12, 0, 0, 0, AR_OFFSET);

        assertThat(calculator.plusOneMonth(jan31).toLocalDate()).hasToString("2026-02-28");
        assertThat(calculator.plusOneMonth(mar31).toLocalDate()).hasToString("2026-04-30");
        assertThat(calculator.plusOneMonth(jan31Leap).toLocalDate()).hasToString("2028-02-29");
    }

    @Test
    void manualPeriodEndWinsAndBecomesTheAnchor() {
        OffsetDateTime manualEnd = NOW.plusDays(90);
        Subscription s = subscription().status(SubscriptionStatus.CANCELED).build();

        PeriodCalculator.Period period = calculator.nextPeriod(s, NOW, manualEnd);

        assertThat(period.start()).isEqualTo(NOW);
        assertThat(period.end()).isEqualTo(manualEnd);
        assertThat(period.anchorDay()).isEqualTo(manualEnd.getDayOfMonth());
    }

    @Test
    void chainedRenewalsKeepTheAnchorDayWithoutDrift() {
        OffsetDateTime jan31 = OffsetDateTime.of(2026, 1, 31, 10, 0, 0, 0, AR_OFFSET);
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodStart(jan31.minusMonths(1))
                .currentPeriodEnd(jan31).billingAnchorDay(31).build();

        PeriodCalculator.Period feb = calculator.nextPeriod(s, jan31, null);
        s.applyPayment(UUID.randomUUID(), feb.start(), feb.end(), feb.anchorDay(), GatewayName.MANUAL,
                Actor.platformAdmin("admin"), null, jan31);
        PeriodCalculator.Period mar = calculator.nextPeriod(s, feb.end(), null);
        s.applyPayment(UUID.randomUUID(), mar.start(), mar.end(), mar.anchorDay(), GatewayName.MANUAL,
                Actor.platformAdmin("admin"), null, feb.end());
        PeriodCalculator.Period apr = calculator.nextPeriod(s, mar.end(), null);

        assertThat(feb.start().toLocalDate()).hasToString("2026-01-31");
        assertThat(feb.end().toLocalDate()).hasToString("2026-02-28");
        assertThat(mar.end().toLocalDate()).hasToString("2026-03-31");
        assertThat(apr.end().toLocalDate()).hasToString("2026-04-30");
        assertThat(apr.end().toLocalTime()).isEqualTo(jan31.toLocalTime());
        assertThat(apr.anchorDay()).isEqualTo(31);
    }

    @Test
    void missingAnchorIsDerivedFromCurrentPeriodStart() {
        OffsetDateTime start = OffsetDateTime.of(2026, 1, 31, 10, 0, 0, 0, AR_OFFSET);
        OffsetDateTime end = OffsetDateTime.of(2026, 2, 28, 10, 0, 0, 0, AR_OFFSET);
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodStart(start)
                .currentPeriodEnd(end).build();

        PeriodCalculator.Period period = calculator.nextPeriod(s, end, null);

        assertThat(period.anchorDay()).isEqualTo(31);
        assertThat(period.end().toLocalDate()).hasToString("2026-03-31");
    }

    @Test
    void newCalendarIsAnchoredAtItsOwnStart() {
        Subscription canceled = subscription().status(SubscriptionStatus.CANCELED).billingAnchorDay(31).build();

        PeriodCalculator.Period period = calculator.nextPeriod(canceled, NOW, null);

        assertThat(period.anchorDay()).isEqualTo(11);
        assertThat(period.end()).isEqualTo(NOW.plusMonths(1));
    }

    @Test
    void extendExclusiveEndAddsExactlyTheRequestedDays() {
        OffsetDateTime midnightEnd = OffsetDateTime.of(2026, 10, 25, 0, 0, 0, 0, AR_OFFSET);
        assertThat(calculator.extendExclusiveEnd(midnightEnd, 7))
                .isEqualTo(OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, AR_OFFSET));

        OffsetDateTime midDayEnd = OffsetDateTime.of(2026, 10, 25, 15, 0, 0, 0, AR_OFFSET);
        assertThat(calculator.extendExclusiveEnd(midDayEnd, 7))
                .isEqualTo(OffsetDateTime.of(2026, 11, 2, 0, 0, 0, 0, AR_OFFSET));
    }

    @Test
    void startOfDayAfterUsesArgentineCalendarDayAndIsExclusive() {
        OffsetDateTime lateUtc = OffsetDateTime.parse("2026-10-12T02:30:00Z");

        OffsetDateTime result = calculator.startOfDayAfter(lateUtc, 14);

        assertThat(result).isEqualTo(OffsetDateTime.of(2026, 10, 26, 0, 0, 0, 0, AR_OFFSET));
    }
}
