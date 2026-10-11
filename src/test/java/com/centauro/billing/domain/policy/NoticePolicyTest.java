package com.centauro.billing.domain.policy;

import com.centauro.billing.domain.enums.NoticeCode;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.Notice;
import com.centauro.billing.domain.model.Subscription;
import org.junit.jupiter.api.Test;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class NoticePolicyTest {

    private final AccessPolicy accessPolicy = new AccessPolicy(TERMS);
    private final NoticePolicy policy = new NoticePolicy(AR);

    private Notice notice(Subscription s, boolean lastPaymentRejected) {
        return policy.notice(s, accessPolicy.evaluate(s, NOW), lastPaymentRejected, NOW);
    }

    @Test
    void rejectedPaymentWinsOverPastDueAndKeepsGraceDays() {
        Subscription s = subscription().status(SubscriptionStatus.PAST_DUE).pastDueReason(PastDueReason.PERIOD_UNPAID)
                .currentPeriodEnd(NOW.minusDays(1)).graceEndsAt(NOW.plusDays(6)).build();

        assertThat(notice(s, false).code()).isEqualTo(NoticeCode.PAYMENT_OVERDUE);
        Notice rejected = notice(s, true);
        assertThat(rejected.code()).isEqualTo(NoticeCode.PAYMENT_REJECTED);
        assertThat(rejected.daysLeft()).isEqualTo(notice(s, false).daysLeft());
    }

    @Test
    void rejectedPaymentOnActiveHasNoDays() {
        Subscription s = subscription().status(SubscriptionStatus.ACTIVE).currentPeriodEnd(NOW.plusDays(20)).build();

        assertThat(notice(s, false)).isNull();
        assertThat(notice(s, true).code()).isEqualTo(NoticeCode.PAYMENT_REJECTED);
        assertThat(notice(s, true).daysLeft()).isNull();
    }

    @Test
    void blockedAndReadOnlyTakePriorityOverRejectedPayment() {
        Subscription blocked = subscription().status(SubscriptionStatus.SUSPENDED)
                .suspensionReason(SuspensionReason.ADMIN_BLOCK).build();

        assertThat(notice(blocked, true).code()).isEqualTo(NoticeCode.BLOCKED);
    }
}
