package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.ActorType;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.InvalidSubscriptionTransitionException;
import com.centauro.billing.domain.exception.SamePlanException;
import com.centauro.billing.domain.vo.Actor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.centauro.billing.domain.enums.SubscriptionStatus.ACTIVE;
import static com.centauro.billing.domain.enums.SubscriptionStatus.CANCELED;
import static com.centauro.billing.domain.enums.SubscriptionStatus.PAST_DUE;
import static com.centauro.billing.domain.enums.SubscriptionStatus.SUSPENDED;
import static com.centauro.billing.domain.enums.SubscriptionStatus.TRIALING;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionTransitionsTest {

    private static final Actor ADMIN = Actor.platformAdmin("admin@centauro");
    private static final Actor JOB = Actor.job("test-job");
    private static final Actor TENANT = Actor.tenantUser("owner");

    private static Subscription inState(SubscriptionStatus status) {
        TestFixturesState state = TestFixturesState.of(status);
        return state.build();
    }

    @Nested
    class Trial {

        @Test
        void t1_startTrialCreatesTrialingWithEvent() {
            UUID planId = UUID.randomUUID();
            Subscription s = Subscription.startTrial("tenant-9", planId, new BigDecimal("25000"),
                    NOW.plusDays(14), "Kiosco", "a@b.com", NOW);

            SubscriptionEvent event = s.trialStartedEvent(Actor.internal("USER_MANAGER"), null, "USER_MANAGER", NOW);

            assertThat(s.getStatus()).isEqualTo(TRIALING);
            assertThat(s.getPriceAmount()).isEqualByComparingTo("25000.00");
            assertThat(event.type()).isEqualTo(SubscriptionEventType.TRIAL_STARTED);
            assertThat(event.fromStatus()).isNull();
            assertThat(event.toStatus()).isEqualTo(TRIALING);
            assertThat(event.actorType()).isEqualTo(ActorType.INTERNAL);
        }

        @Test
        void t2_extendTrialMovesTheEndDate() {
            Subscription s = subscription().trialEndsAt(NOW.plusDays(2)).build();

            SubscriptionEvent event = s.extendTrial(NOW.plusDays(9), ADMIN, "demo", NOW);

            assertThat(s.getTrialEndsAt()).isEqualTo(NOW.plusDays(9));
            assertThat(event.type()).isEqualTo(SubscriptionEventType.TRIAL_EXTENDED);
            assertThat(event.reason()).isEqualTo("demo");
        }

        @Test
        void t2_extendTrialRejectsEarlierDate() {
            Subscription s = subscription().trialEndsAt(NOW.plusDays(5)).build();

            assertThatThrownBy(() -> s.extendTrial(NOW.plusDays(4), ADMIN, "x", NOW))
                    .isInstanceOf(InvalidBillingRequestException.class);
        }

        @Test
        void t2b_extendTrialFromPastDueTrialExpiredReturnsToTrialing() {
            Subscription s = subscription().status(PAST_DUE).pastDueReason(PastDueReason.TRIAL_EXPIRED)
                    .trialEndsAt(NOW.minusDays(1)).graceEndsAt(NOW.plusDays(2)).build();

            s.extendTrial(NOW.plusDays(7), ADMIN, "x", NOW);

            assertThat(s.getStatus()).isEqualTo(TRIALING);
            assertThat(s.getPastDueReason()).isNull();
            assertThat(s.getGraceEndsAt()).isNull();
        }

        @Test
        void t2b_extendTrialFromPastDuePeriodUnpaidIsInvalid() {
            Subscription s = subscription().status(PAST_DUE).pastDueReason(PastDueReason.PERIOD_UNPAID)
                    .currentPeriodEnd(NOW.minusDays(1)).graceEndsAt(NOW.plusDays(6)).build();

            assertThatThrownBy(() -> s.extendTrial(NOW.plusDays(7), ADMIN, "x", NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @ParameterizedTest
        @EnumSource(value = SubscriptionStatus.class, names = {"ACTIVE", "SUSPENDED", "CANCELED"})
        void t2_extendTrialInvalidFromOtherStates(SubscriptionStatus status) {
            Subscription s = inState(status);

            assertThatThrownBy(() -> s.extendTrial(NOW.plusDays(7), ADMIN, "x", NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @Test
        void t3_expiredTrialGoesPastDueWithTrialGrace() {
            OffsetDateTime trialEnd = NOW.minusHours(1);
            Subscription s = subscription().trialEndsAt(trialEnd).build();

            SubscriptionEvent event = s.expireTrial(3, JOB, NOW);

            assertThat(s.getStatus()).isEqualTo(PAST_DUE);
            assertThat(s.getPastDueReason()).isEqualTo(PastDueReason.TRIAL_EXPIRED);
            assertThat(s.getGraceEndsAt()).isEqualTo(trialEnd.plusDays(3));
            assertThat(event.dedupeKey()).isEqualTo("TRIAL_EXPIRED:" + trialEnd.toInstant());
        }

        @Test
        void t4_expiredTrialWithScheduledCancellationGoesCanceled() {
            Subscription s = subscription().trialEndsAt(NOW.minusHours(1)).cancelAtPeriodEnd(true).build();

            SubscriptionEvent event = s.expireTrial(3, JOB, NOW);

            assertThat(s.getStatus()).isEqualTo(CANCELED);
            assertThat(s.getCanceledAt()).isEqualTo(NOW);
            assertThat(event.type()).isEqualTo(SubscriptionEventType.TRIAL_ENDED_CANCELED);
        }

        @Test
        void t3_trialNotYetExpiredIsInvalid() {
            Subscription s = subscription().trialEndsAt(NOW.plusHours(1)).build();

            assertThatThrownBy(() -> s.expireTrial(3, JOB, NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }
    }

    @Nested
    class Periods {

        @Test
        void t6_expiredPeriodGoesPastDueWithGrace() {
            OffsetDateTime end = NOW.minusMinutes(5);
            Subscription s = subscription().status(ACTIVE).currentPeriodEnd(end).build();

            SubscriptionEvent event = s.endPeriod(7, JOB, NOW);

            assertThat(s.getStatus()).isEqualTo(PAST_DUE);
            assertThat(s.getPastDueReason()).isEqualTo(PastDueReason.PERIOD_UNPAID);
            assertThat(s.getGraceEndsAt()).isEqualTo(end.plusDays(7));
            assertThat(event.dedupeKey()).isEqualTo("PERIOD_UNPAID:" + end.toInstant());
        }

        @Test
        void t7_expiredPeriodWithScheduledCancellationGoesCanceled() {
            Subscription s = subscription().status(ACTIVE).currentPeriodEnd(NOW.minusMinutes(5))
                    .cancelAtPeriodEnd(true).build();

            s.endPeriod(7, JOB, NOW);

            assertThat(s.getStatus()).isEqualTo(CANCELED);
        }

        @Test
        void t8_expiredGraceSuspendsForNonPayment() {
            Subscription s = subscription().status(PAST_DUE).pastDueReason(PastDueReason.PERIOD_UNPAID)
                    .currentPeriodEnd(NOW.minusDays(8)).graceEndsAt(NOW.minusDays(1)).build();

            SubscriptionEvent event = s.suspendForNonPayment(JOB, NOW);

            assertThat(s.getStatus()).isEqualTo(SUSPENDED);
            assertThat(s.getSuspensionReason()).isEqualTo(SuspensionReason.NON_PAYMENT);
            assertThat(s.getSuspendedAt()).isEqualTo(NOW);
            assertThat(event.dedupeKey()).startsWith("SUSPENDED_NON_PAYMENT:");
        }

        @Test
        void t8_graceNotExpiredIsInvalid() {
            Subscription s = subscription().status(PAST_DUE).pastDueReason(PastDueReason.PERIOD_UNPAID)
                    .currentPeriodEnd(NOW.minusDays(1)).graceEndsAt(NOW.plusDays(6)).build();

            assertThatThrownBy(() -> s.suspendForNonPayment(JOB, NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }
    }

    @Nested
    class Payments {

        @ParameterizedTest
        @EnumSource(value = SubscriptionStatus.class)
        void t5_paymentActivatesFromEveryAllowedState(SubscriptionStatus status) {
            Subscription s = inState(status);
            UUID paymentId = UUID.randomUUID();

            SubscriptionEvent event = s.applyPayment(paymentId, NOW, NOW.plusMonths(1), 11, GatewayName.MANUAL, ADMIN,
                    "transferencia", NOW);

            assertThat(s.getStatus()).isEqualTo(ACTIVE);
            assertThat(s.getCurrentPeriodEnd()).isEqualTo(NOW.plusMonths(1));
            assertThat(s.getGraceEndsAt()).isNull();
            assertThat(s.getSuspensionReason()).isNull();
            assertThat(s.getPastDueReason()).isNull();
            assertThat(s.getGateway()).isEqualTo(GatewayName.MANUAL);
            assertThat(s.getBillingAnchorDay()).isEqualTo(11);
            assertThat(event.dedupeKey()).isEqualTo("PAYMENT_APPLIED:" + paymentId);
        }

        @ParameterizedTest
        @EnumSource(value = SuspensionReason.class, names = {"ADMIN_READ_ONLY", "ADMIN_BLOCK"})
        void t5_paymentWithAdminSuspensionDoesNotReactivate(SuspensionReason reason) {
            Subscription s = subscription().status(SUSPENDED).suspensionReason(reason).build();

            assertThatThrownBy(() -> s.applyPayment(UUID.randomUUID(), NOW, NOW.plusMonths(1), 11, GatewayName.MANUAL,
                    ADMIN, "x", NOW)).isInstanceOf(InvalidSubscriptionTransitionException.class);

            SubscriptionEvent recorded = s.recordPaymentWithoutReactivation(UUID.randomUUID(), ADMIN, "x", NOW);
            assertThat(s.getStatus()).isEqualTo(SUSPENDED);
            assertThat(recorded.type()).isEqualTo(SubscriptionEventType.PAYMENT_RECORDED);
        }

        @Test
        void t5_paymentOnCanceledClearsCancellation() {
            Subscription s = inState(CANCELED);

            s.applyPayment(UUID.randomUUID(), NOW, NOW.plusMonths(1), 11, GatewayName.MANUAL, ADMIN, "x", NOW);

            assertThat(s.getCanceledAt()).isNull();
            assertThat(s.isCancelAtPeriodEnd()).isFalse();
        }
    }

    @Nested
    class Cancellation {

        @ParameterizedTest
        @EnumSource(value = SubscriptionStatus.class, names = {"TRIALING", "ACTIVE"})
        void t9_cancelSchedulesAtPeriodEnd(SubscriptionStatus status) {
            Subscription s = inState(status);

            SubscriptionEvent event = s.cancel(TENANT, "caro", NOW);

            assertThat(s.getStatus()).isEqualTo(status);
            assertThat(s.isCancelAtPeriodEnd()).isTrue();
            assertThat(event.type()).isEqualTo(SubscriptionEventType.CANCELLATION_SCHEDULED);
        }

        @Test
        void t9_cancelTwiceIsInvalid() {
            Subscription s = inState(TRIALING);
            s.cancel(TENANT, null, NOW);

            assertThatThrownBy(() -> s.cancel(TENANT, null, NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @Test
        void t10_cancelPastDueIsImmediate() {
            Subscription s = inState(PAST_DUE);

            SubscriptionEvent event = s.cancel(TENANT, null, NOW);

            assertThat(s.getStatus()).isEqualTo(CANCELED);
            assertThat(s.getGraceEndsAt()).isNull();
            assertThat(event.type()).isEqualTo(SubscriptionEventType.CANCELED);
        }

        @Test
        void t10_cancelSuspendedForNonPaymentIsImmediate() {
            Subscription s = inState(SUSPENDED);

            s.cancel(TENANT, null, NOW);

            assertThat(s.getStatus()).isEqualTo(CANCELED);
            assertThat(s.getSuspensionReason()).isNull();
        }

        @Test
        void t10_cancelAdminSuspendedIsInvalidForTenant() {
            Subscription s = subscription().status(SUSPENDED).suspensionReason(SuspensionReason.ADMIN_BLOCK).build();

            assertThatThrownBy(() -> s.cancel(TENANT, null, NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @Test
        void cancelCanceledIsInvalid() {
            Subscription s = inState(CANCELED);

            assertThatThrownBy(() -> s.cancel(TENANT, null, NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
            assertThatThrownBy(() -> s.cancelImmediately(ADMIN, "x", NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @Test
        void platformImmediateCancelWorksFromActive() {
            Subscription s = inState(ACTIVE);

            s.cancelImmediately(ADMIN, "fraude", NOW);

            assertThat(s.getStatus()).isEqualTo(CANCELED);
        }
    }

    @Nested
    class Platform {

        @ParameterizedTest
        @EnumSource(value = SubscriptionStatus.class, names = {"TRIALING", "ACTIVE", "PAST_DUE", "SUSPENDED"})
        void t11_suspendByAdmin(SubscriptionStatus status) {
            Subscription s = inState(status);

            s.suspendByAdmin(SuspensionReason.ADMIN_BLOCK, ADMIN, "fraude", NOW);

            assertThat(s.getStatus()).isEqualTo(SUSPENDED);
            assertThat(s.getSuspensionReason()).isEqualTo(SuspensionReason.ADMIN_BLOCK);
        }

        @Test
        void t11_suspendCanceledIsInvalid() {
            assertThatThrownBy(() -> inState(CANCELED).suspendByAdmin(SuspensionReason.ADMIN_READ_ONLY, ADMIN, "x", NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @Test
        void t11_suspendWithNonPaymentReasonIsRejected() {
            assertThatThrownBy(() -> inState(ACTIVE).suspendByAdmin(SuspensionReason.NON_PAYMENT, ADMIN, "x", NOW))
                    .isInstanceOf(InvalidBillingRequestException.class);
        }

        @ParameterizedTest
        @EnumSource(value = SubscriptionStatus.class, names = {"SUSPENDED", "CANCELED"})
        void t12_reactivate(SubscriptionStatus status) {
            Subscription s = inState(status);

            s.reactivate(NOW.plusDays(30), 10, ADMIN, "acuerdo", NOW);

            assertThat(s.getStatus()).isEqualTo(ACTIVE);
            assertThat(s.getCurrentPeriodEnd()).isEqualTo(NOW.plusDays(30));
            assertThat(s.getBillingAnchorDay()).isEqualTo(10);
            assertThat(s.getSuspensionReason()).isNull();
            assertThat(s.getCanceledAt()).isNull();
        }

        @Test
        void t12_reactivateRequiresFutureEnd() {
            assertThatThrownBy(() -> inState(SUSPENDED).reactivate(NOW, 11, ADMIN, "x", NOW))
                    .isInstanceOf(InvalidBillingRequestException.class);
        }

        @ParameterizedTest
        @EnumSource(value = SubscriptionStatus.class, names = {"TRIALING", "ACTIVE", "PAST_DUE"})
        void t12_reactivateFromOtherStatesIsInvalid(SubscriptionStatus status) {
            assertThatThrownBy(() -> inState(status).reactivate(NOW.plusDays(30), 10, ADMIN, "x", NOW))
                    .isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @ParameterizedTest
        @EnumSource(value = SubscriptionStatus.class, names = {"TRIALING", "ACTIVE", "PAST_DUE", "SUSPENDED"})
        void t13_changePlan(SubscriptionStatus status) {
            Subscription s = inState(status);
            UUID newPlan = UUID.randomUUID();

            SubscriptionEvent event = s.changePlan(newPlan, "INICIAL", new BigDecimal("10000"), ADMIN, "x", NOW);

            assertThat(s.getPlanId()).isEqualTo(newPlan);
            assertThat(s.getPriceAmount()).isEqualByComparingTo("10000.00");
            assertThat(s.getStatus()).isEqualTo(status);
            assertThat(event.data()).containsEntry("toPlanCode", "INICIAL").containsEntry("fromPrice", "25000.00");
        }

        @Test
        void t13_changePlanOnCanceledIsInvalid() {
            assertThatThrownBy(() -> inState(CANCELED).changePlan(UUID.randomUUID(), "INICIAL", BigDecimal.TEN,
                    ADMIN, "x", NOW)).isInstanceOf(InvalidSubscriptionTransitionException.class);
        }

        @Test
        void t13_changeToSamePlanIsRejected() {
            Subscription s = inState(ACTIVE);

            assertThatThrownBy(() -> s.changePlan(s.getPlanId(), "PROFESIONAL", BigDecimal.TEN, ADMIN, "x", NOW))
                    .isInstanceOf(SamePlanException.class);
        }
    }

    /** Suscripciones válidas para cada estado, coherentes con los CHECK de V2. */
    private record TestFixturesState(SubscriptionStatus status) {

        static TestFixturesState of(SubscriptionStatus status) {
            return new TestFixturesState(status);
        }

        Subscription build() {
            return switch (status) {
                case TRIALING -> subscription().build();
                case ACTIVE -> subscription().status(ACTIVE).currentPeriodStart(NOW.minusDays(10))
                        .currentPeriodEnd(NOW.plusDays(20)).build();
                case PAST_DUE -> subscription().status(PAST_DUE).pastDueReason(PastDueReason.PERIOD_UNPAID)
                        .currentPeriodEnd(NOW.minusDays(1)).graceEndsAt(NOW.plusDays(6)).build();
                case SUSPENDED -> subscription().status(SUSPENDED).suspensionReason(SuspensionReason.NON_PAYMENT)
                        .currentPeriodEnd(NOW.minusDays(10)).graceEndsAt(NOW.minusDays(3)).build();
                case CANCELED -> subscription().status(CANCELED).trialEndsAt(NOW.minusDays(5)).build();
            };
        }
    }
}
