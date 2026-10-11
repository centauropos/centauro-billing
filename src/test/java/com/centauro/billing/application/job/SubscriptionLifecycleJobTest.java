package com.centauro.billing.application.job;

import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.JobLockPort;
import com.centauro.billing.support.InMemoryEventRepository;
import com.centauro.billing.support.InMemorySubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionLifecycleJobTest {

    private InMemorySubscriptionRepository subscriptions;
    private InMemoryEventRepository events;
    private FakeLock lock;
    private SubscriptionLifecycleJob job;

    @BeforeEach
    void setUp() {
        subscriptions = new InMemorySubscriptionRepository();
        events = new InMemoryEventRepository();
        lock = new FakeLock();
        job = new SubscriptionLifecycleJob(subscriptions, new LifecycleStepProcessor(subscriptions, events, TERMS),
                lock, clockAt(NOW));
    }

    @Test
    void processesTrialsPeriodsAndGraceInOrder() {
        Subscription expiredTrial = store(subscription().tenantId("t1").trialEndsAt(NOW.minusHours(2)).build());
        Subscription expiredTrialToCancel = store(subscription().tenantId("t2").trialEndsAt(NOW.minusHours(2))
                .cancelAtPeriodEnd(true).build());
        Subscription expiredPeriod = store(subscription().tenantId("t3").status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(NOW.minusMinutes(1)).build());
        Subscription expiredGrace = store(subscription().tenantId("t4").status(SubscriptionStatus.PAST_DUE)
                .pastDueReason(PastDueReason.PERIOD_UNPAID).currentPeriodEnd(NOW.minusDays(8))
                .graceEndsAt(NOW.minusDays(1)).build());
        Subscription healthy = store(subscription().tenantId("t5").trialEndsAt(NOW.plusDays(3)).build());

        assertThat(job.run()).isTrue();

        assertThat(expiredTrial.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(expiredTrialToCancel.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(expiredPeriod.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(expiredGrace.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(healthy.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        List<SubscriptionEventType> types = events.types();
        assertThat(types).hasSize(4);
        assertThat(types.subList(0, 2)).containsExactlyInAnyOrder(
                SubscriptionEventType.TRIAL_EXPIRED, SubscriptionEventType.TRIAL_ENDED_CANCELED);
        assertThat(types.subList(2, 4)).containsExactly(
                SubscriptionEventType.PERIOD_UNPAID, SubscriptionEventType.SUSPENDED_NON_PAYMENT);
        assertThat(lock.keys).containsExactly(SubscriptionLifecycleJob.LOCK_KEY);
    }

    @Test
    void trialWhoseGraceAlreadyEndedIsSuspendedInTheSameRun() {
        Subscription s = store(subscription().trialEndsAt(NOW.minusDays(5)).build());

        job.run();

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(events.types()).containsExactly(SubscriptionEventType.TRIAL_EXPIRED,
                SubscriptionEventType.SUSPENDED_NON_PAYMENT);
    }

    @Test
    void twoRunsProduceASingleEvent() {
        store(subscription().trialEndsAt(NOW.minusHours(2)).build());

        job.run();
        job.run();

        assertThat(events.events).hasSize(1);
    }

    @Test
    void doesNothingWhenLockIsNotAcquired() {
        Subscription s = store(subscription().trialEndsAt(NOW.minusHours(2)).build());
        lock.available = false;

        assertThat(job.run()).isFalse();

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(events.events).isEmpty();
        assertThat(subscriptions.saves).isZero();
    }

    @Test
    void skipsRowsLockedByAnotherTransaction() {
        Subscription s = store(subscription().trialEndsAt(NOW.minusHours(2)).build());
        subscriptions.lockedByOthers.add(s.getId());

        job.run();

        assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(events.events).isEmpty();
    }

    @Test
    void rowsThatAlwaysFailDoNotBlockTheRestOfTheBatch() {
        List<Subscription> due = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            due.add(store(subscription().tenantId("t" + i).trialEndsAt(NOW.minusHours(1)).build()));
        }
        List<UUID> byCursorOrder = due.stream().map(Subscription::getId)
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        Set<UUID> failing = Set.copyOf(byCursorOrder.subList(0, 4));
        AtomicInteger attempts = new AtomicInteger();
        LifecycleStepProcessor flaky = new LifecycleStepProcessor(subscriptions, events, TERMS) {
            @Override
            public boolean expireTrial(UUID subscriptionId, java.time.OffsetDateTime now) {
                if (failing.contains(subscriptionId)) {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("fila corrupta");
                }
                return super.expireTrial(subscriptionId, now);
            }
        };
        SubscriptionLifecycleJob smallBatches = new SubscriptionLifecycleJob(subscriptions, flaky, lock,
                clockAt(NOW), 2);

        smallBatches.run();

        assertThat(due).filteredOn(s -> !failing.contains(s.getId()))
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE));
        assertThat(due).filteredOn(s -> failing.contains(s.getId()))
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.TRIALING));
        assertThat(attempts).hasValue(4);
    }

    private Subscription store(Subscription s) {
        subscriptions.put(s);
        return s;
    }

    private static final class FakeLock implements JobLockPort {
        boolean available = true;
        final List<Long> keys = new ArrayList<>();

        @Override
        public boolean runExclusively(long lockKey, Runnable task) {
            keys.add(lockKey);
            if (!available) {
                return false;
            }
            task.run();
            return true;
        }
    }
}
