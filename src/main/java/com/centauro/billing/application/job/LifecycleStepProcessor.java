package com.centauro.billing.application.job;

import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.model.SubscriptionEvent;
import com.centauro.billing.domain.policy.BillingTerms;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Una transacción por suscripción. La fila se bloquea con SKIP LOCKED y la condición se reevalúa sobre
 * la fila bloqueada: si ya no aplica, no hace nada.
 */
@Component
public class LifecycleStepProcessor {

    static final Actor JOB_ACTOR = Actor.job("SubscriptionLifecycleJob");

    private final SubscriptionRepositoryPort subscriptions;
    private final SubscriptionEventRepositoryPort events;
    private final BillingTerms terms;

    public LifecycleStepProcessor(SubscriptionRepositoryPort subscriptions, SubscriptionEventRepositoryPort events,
                                  BillingTerms terms) {
        this.subscriptions = subscriptions;
        this.events = events;
        this.terms = terms;
    }

    /** T3 / T4. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireTrial(UUID subscriptionId, OffsetDateTime now) {
        return process(subscriptionId, s -> s.isTrialExpired(now),
                s -> s.expireTrial(terms.trialGraceDays(), JOB_ACTOR, now));
    }

    /** T6 / T7. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean endPeriod(UUID subscriptionId, OffsetDateTime now) {
        return process(subscriptionId, s -> s.isPeriodExpired(now),
                s -> s.endPeriod(terms.graceDays(), JOB_ACTOR, now));
    }

    /** T8. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean suspendForNonPayment(UUID subscriptionId, OffsetDateTime now) {
        return process(subscriptionId, s -> s.isGraceExpired(now), s -> s.suspendForNonPayment(JOB_ACTOR, now));
    }

    private boolean process(UUID subscriptionId, Predicate<Subscription> applies,
                            Function<Subscription, SubscriptionEvent> transition) {
        Optional<Subscription> locked = subscriptions.findByIdForUpdateSkipLocked(subscriptionId);
        if (locked.isEmpty() || !applies.test(locked.get())) {
            return false;
        }
        Subscription subscription = locked.get();
        SubscriptionEvent event = transition.apply(subscription);
        subscriptions.save(subscription);
        events.append(event);
        return true;
    }
}
