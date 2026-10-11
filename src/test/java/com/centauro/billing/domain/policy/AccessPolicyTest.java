package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.AccessLevel;
import com.centauro.billing.domain.enums.NoticeCode;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.AccessDecision;
import com.centauro.billing.domain.model.Notice;
import com.centauro.billing.domain.model.Subscription;
import org.junit.jupiter.api.Test;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class AccessPolicyTest {

    private final AccessPolicy policy = new AccessPolicy(TERMS);
    private final NoticePolicy noticePolicy = new NoticePolicy(AR);

    @Test
    void unknownTenantIsFullAndUnknown() {
        AccessDecision decision = policy.evaluate(null, NOW);

        assertThat(decision.known()).isFalse();
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.FULL);
        assertThat(decision.status()).isNull();
    }

    @Test
    void trialingActiveAndPastDueAreFull() {
        assertThat(policy.evaluate(subscription().build(), NOW).accessLevel()).isEqualTo(AccessLevel.FULL);
        assertThat(policy.evaluate(subscription().status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.plusDays(3)).build(), NOW).accessLevel()).isEqualTo(AccessLevel.FULL);
        assertThat(policy.evaluate(subscription().status(SubscriptionStatus.PAST_DUE)
                .pastDueReason(PastDueReason.PERIOD_UNPAID).currentPeriodEnd(NOW.minusDays(1))
                .graceEndsAt(NOW.plusDays(6)).build(), NOW).accessLevel()).isEqualTo(AccessLevel.FULL);
    }

    @Test
    void suspendedByNonPaymentOrReadOnlyIsReadOnly() {
        assertThat(policy.evaluate(suspended(SuspensionReason.NON_PAYMENT), NOW).accessLevel())
                .isEqualTo(AccessLevel.READ_ONLY);
        assertThat(policy.evaluate(suspended(SuspensionReason.ADMIN_READ_ONLY), NOW).accessLevel())
                .isEqualTo(AccessLevel.READ_ONLY);
    }

    @Test
    void suspendedByAdminBlockIsBlocked() {
        AccessDecision decision = policy.evaluate(suspended(SuspensionReason.ADMIN_BLOCK), NOW);

        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.BLOCKED);
        Notice notice = noticePolicy.notice(suspended(SuspensionReason.ADMIN_BLOCK), decision, NOW);
        assertThat(notice.code()).isEqualTo(NoticeCode.BLOCKED);
    }

    @Test
    void canceledIsReadOnly() {
        Subscription canceled = subscription().status(SubscriptionStatus.CANCELED).build();

        assertThat(policy.evaluate(canceled, NOW).accessLevel()).isEqualTo(AccessLevel.READ_ONLY);
    }

    @Test
    void expiredTrialWithLateJobIsEffectivelyPastDueWithTrialGrace() {
        Subscription s = subscription().trialEndsAt(NOW.minusDays(1)).build();

        AccessDecision decision = policy.evaluate(s, NOW);

        assertThat(decision.status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(decision.effectiveStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(decision.effectiveGraceEndsAt()).isEqualTo(NOW.minusDays(1).plusDays(3));
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.FULL);
        assertThat(noticePolicy.notice(s, decision, NOW).code()).isEqualTo(NoticeCode.PAYMENT_OVERDUE);
    }

    @Test
    void expiredTrialBeyondGraceIsEffectivelySuspended() {
        Subscription s = subscription().trialEndsAt(NOW.minusDays(4)).build();

        AccessDecision decision = policy.evaluate(s, NOW);

        assertThat(decision.effectiveStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.READ_ONLY);
    }

    @Test
    void expiredActivePeriodWithLateJobIsEffectivelyPastDue() {
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.minusDays(2)).build();

        AccessDecision decision = policy.evaluate(s, NOW);

        assertThat(decision.effectiveStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(decision.effectiveGraceEndsAt()).isEqualTo(NOW.minusDays(2).plusDays(7));
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.FULL);
    }

    @Test
    void expiredActivePeriodWithScheduledCancellationIsEffectivelyCanceled() {
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.minusDays(1))
                .cancelAtPeriodEnd(true).build();

        assertThat(policy.evaluate(s, NOW).effectiveStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(policy.evaluate(s, NOW).accessLevel()).isEqualTo(AccessLevel.READ_ONLY);
    }

    @Test
    void pastDueWithExpiredGraceIsEffectivelySuspended() {
        Subscription s = subscription().status(SubscriptionStatus.PAST_DUE).pastDueReason(PastDueReason.PERIOD_UNPAID)
                .currentPeriodEnd(NOW.minusDays(8)).graceEndsAt(NOW.minusHours(1)).build();

        AccessDecision decision = policy.evaluate(s, NOW);

        assertThat(decision.effectiveStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(decision.effectiveSuspensionReason()).isEqualTo(SuspensionReason.NON_PAYMENT);
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.READ_ONLY);
    }

    @Test
    void trialNoticeSwitchesToEndingInTheLastThreeDays() {
        Subscription longTrial = subscription().trialEndsAt(NOW.plusDays(14)).build();
        Subscription shortTrial = subscription().trialEndsAt(NOW.plusDays(2)).build();

        Notice longNotice = noticePolicy.notice(longTrial, policy.evaluate(longTrial, NOW), NOW);
        Notice shortNotice = noticePolicy.notice(shortTrial, policy.evaluate(shortTrial, NOW), NOW);

        assertThat(longNotice.code()).isEqualTo(NoticeCode.TRIAL_ACTIVE);
        assertThat(longNotice.daysLeft()).isEqualTo(14);
        assertThat(shortNotice.code()).isEqualTo(NoticeCode.TRIAL_ENDING);
        assertThat(shortNotice.daysLeft()).isEqualTo(2);
    }

    @Test
    void scheduledCancellationNotice() {
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(5))
                .cancelAtPeriodEnd(true).build();

        Notice notice = noticePolicy.notice(s, policy.evaluate(s, NOW), NOW);

        assertThat(notice.code()).isEqualTo(NoticeCode.CANCELLATION_SCHEDULED);
        assertThat(notice.daysLeft()).isEqualTo(5);
    }

    @Test
    void activeUpToDateHasNoNotice() {
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(5)).build();

        assertThat(noticePolicy.notice(s, policy.evaluate(s, NOW), NOW)).isNull();
    }

    private static Subscription suspended(SuspensionReason reason) {
        return subscription().status(SubscriptionStatus.SUSPENDED).suspensionReason(reason)
                .currentPeriodEnd(NOW.minusDays(10)).build();
    }
}
