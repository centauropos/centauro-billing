package com.centauro.billing.application.job;

import com.centauro.billing.domain.port.JobLockPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Paso del tiempo: T4/T3 (trial vencido), después T7/T6 (fin de período) y por último T8 (fin de gracia).
 * Corre bajo {@code pg_try_advisory_xact_lock(1001)}: si otra instancia lo tiene, sale sin hacer nada.
 */
@Component
@ConditionalOnProperty(name = "billing.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionLifecycleJob {

    public static final long LOCK_KEY = 1001L;
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES_PER_STEP = 50;

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleJob.class);

    private final SubscriptionRepositoryPort subscriptions;
    private final LifecycleStepProcessor processor;
    private final JobLockPort jobLock;
    private final Clock clock;

    public SubscriptionLifecycleJob(SubscriptionRepositoryPort subscriptions, LifecycleStepProcessor processor,
                                    JobLockPort jobLock, Clock clock) {
        this.subscriptions = subscriptions;
        this.processor = processor;
        this.jobLock = jobLock;
        this.clock = clock;
    }

    @Scheduled(cron = "${billing.jobs.lifecycle-cron}", zone = "${billing.zone}")
    public void scheduledRun() {
        run();
    }

    /** Devuelve false si no obtuvo el lock. */
    public boolean run() {
        boolean acquired = jobLock.runExclusively(LOCK_KEY, () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            int trials = step("trial vencido", now, subscriptions::findTrialsDue, processor::expireTrial);
            int periods = step("fin de período", now, subscriptions::findPeriodsDue, processor::endPeriod);
            int graces = step("fin de gracia", now, subscriptions::findGraceDue, processor::suspendForNonPayment);
            if (trials + periods + graces > 0) {
                log.info("SubscriptionLifecycleJob: trials={} periodos={} suspensiones={}", trials, periods, graces);
            }
        });
        if (!acquired) {
            log.info("SubscriptionLifecycleJob: lock {} tomado por otra instancia, se omite", LOCK_KEY);
        }
        return acquired;
    }

    private int step(String name, OffsetDateTime now, BiFunction<OffsetDateTime, Integer, List<UUID>> candidates,
                     BiPredicate<UUID, OffsetDateTime> transition) {
        int processed = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_STEP; batch++) {
            List<UUID> ids = candidates.apply(now, BATCH_SIZE);
            int processedInBatch = 0;
            for (UUID id : ids) {
                try {
                    if (transition.test(id, now)) {
                        processedInBatch++;
                    }
                } catch (RuntimeException e) {
                    log.error("SubscriptionLifecycleJob: falló '{}' para la suscripción {}", name, id, e);
                }
            }
            processed += processedInBatch;
            if (ids.size() < BATCH_SIZE || processedInBatch == 0) {
                break;
            }
        }
        return processed;
    }
}
