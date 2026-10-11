package com.centauro.billing.application.job;

import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import com.centauro.billing.domain.port.JobLockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Vence los checkouts PENDING con {@code expires_at <= now} y cancela su preapproval en la pasarela (best
 * effort: si falla, la conciliación lo vuelve a intentar). Lock {@code 1002}; cursor por id como
 * {@link SubscriptionLifecycleJob}.
 */
@Component
@ConditionalOnProperty(name = "billing.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CheckoutExpirationJob {

    public static final long LOCK_KEY = 1002L;
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES = 50;

    private static final Logger log = LoggerFactory.getLogger(CheckoutExpirationJob.class);

    private final CheckoutRepositoryPort checkouts;
    private final CheckoutExpirationProcessor processor;
    private final GatewayOperations gatewayOperations;
    private final JobLockPort jobLock;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public CheckoutExpirationJob(CheckoutRepositoryPort checkouts, CheckoutExpirationProcessor processor,
                                 GatewayOperations gatewayOperations, JobLockPort jobLock, Clock clock) {
        this(checkouts, processor, gatewayOperations, jobLock, clock, BATCH_SIZE);
    }

    CheckoutExpirationJob(CheckoutRepositoryPort checkouts, CheckoutExpirationProcessor processor,
                          GatewayOperations gatewayOperations, JobLockPort jobLock, Clock clock, int batchSize) {
        this.checkouts = checkouts;
        this.processor = processor;
        this.gatewayOperations = gatewayOperations;
        this.jobLock = jobLock;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${billing.jobs.checkout-expiration-cron}", zone = "${billing.zone}")
    public void scheduledRun() {
        run();
    }

    /** Devuelve false si no obtuvo el lock. */
    public boolean run() {
        boolean acquired = jobLock.runExclusively(LOCK_KEY, () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            int expired = 0;
            UUID cursor = SubscriptionLifecycleJob.CURSOR_START;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<UUID> ids = checkouts.findExpiredPending(now, cursor, batchSize);
                for (UUID id : ids) {
                    try {
                        Optional<GatewayCheckout> checkout = processor.expire(id, now);
                        if (checkout.isPresent()) {
                            expired++;
                            gatewayOperations.cancelQuietly(checkout.get().getGatewayPreapprovalId(),
                                    "checkout vencido " + id);
                        }
                    } catch (RuntimeException e) {
                        log.error("CheckoutExpirationJob: falló el checkout {}", id, e);
                    }
                }
                if (ids.size() < batchSize) {
                    break;
                }
                cursor = ids.get(ids.size() - 1);
            }
            if (expired > 0) {
                log.info("CheckoutExpirationJob: vencidos={}", expired);
            }
        });
        if (!acquired) {
            log.info("CheckoutExpirationJob: lock {} tomado por otra instancia, se omite", LOCK_KEY);
        }
        return acquired;
    }
}
