package com.centauro.billing.application.job;

import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.GatewaySyncService;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import com.centauro.billing.domain.port.JobLockPort;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
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
 * Vence los checkouts PENDING con {@code expires_at <= now} (lock {@code 1002}; cursor por id como
 * {@link SubscriptionLifecycleJob}). Si no hay {@code preapprovalId} (prepare sin authorize), solo marca
 * EXPIRED. Si hay preapproval (legacy pending/init_point o authorize que no llegó el webhook):
 * <ul>
 *   <li>autorizado: se aplica con el flujo normal de {@link GatewaySyncService};</li>
 *   <li>vinculado a la suscripción (AUTHORIZED): no se cancela;</li>
 *   <li>cualquier otro estado vivo: se cancela (best effort).</li>
 * </ul>
 * Si la reconsulta falla no se cancela nada: lo retoma la conciliación.
 */
@Component
@ConditionalOnProperty(name = "billing.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CheckoutExpirationJob {

    public static final long LOCK_KEY = 1002L;
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES = 50;
    static final Actor JOB_ACTOR = Actor.job("CheckoutExpirationJob");

    private static final Logger log = LoggerFactory.getLogger(CheckoutExpirationJob.class);

    private final CheckoutRepositoryPort checkouts;
    private final CheckoutExpirationProcessor processor;
    private final GatewayOperations gatewayOperations;
    private final GatewaySyncService sync;
    private final JobLockPort jobLock;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public CheckoutExpirationJob(CheckoutRepositoryPort checkouts, CheckoutExpirationProcessor processor,
                                 GatewayOperations gatewayOperations, GatewaySyncService sync, JobLockPort jobLock,
                                 Clock clock) {
        this(checkouts, processor, gatewayOperations, sync, jobLock, clock, BATCH_SIZE);
    }

    CheckoutExpirationJob(CheckoutRepositoryPort checkouts, CheckoutExpirationProcessor processor,
                          GatewayOperations gatewayOperations, GatewaySyncService sync, JobLockPort jobLock,
                          Clock clock, int batchSize) {
        this.checkouts = checkouts;
        this.processor = processor;
        this.gatewayOperations = gatewayOperations;
        this.sync = sync;
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
                            settlePreapproval(checkout.get(), now);
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

    private void settlePreapproval(GatewayCheckout checkout, OffsetDateTime now) {
        String preapprovalId = checkout.getGatewayPreapprovalId();
        Optional<PaymentGateway> gateway = gatewayOperations.gateway();
        if (preapprovalId == null || gateway.isEmpty()) {
            return;
        }
        GatewaySubscriptionSnapshot snapshot;
        try {
            snapshot = gateway.get().getSubscription(preapprovalId);
        } catch (RuntimeException e) {
            log.warn("CheckoutExpirationJob: no se pudo consultar el preapproval {} del checkout {}: {}; "
                    + "lo retoma la conciliación", preapprovalId, checkout.getId(), e.getMessage());
            return;
        }
        if (snapshot.status() == GatewaySubscriptionStatus.AUTHORIZED) {
            GatewaySyncService.Outcome outcome = sync.applyPreapproval(snapshot, JOB_ACTOR, now);
            log.info("CheckoutExpirationJob: checkout {} vencido con preapproval {} autorizado: {}", checkout.getId(),
                    preapprovalId, outcome.detail());
            return;
        }
        if (snapshot.status() == GatewaySubscriptionStatus.CANCELLED || sync.isLinked(preapprovalId)) {
            return;
        }
        gatewayOperations.cancelQuietly(preapprovalId, "checkout vencido " + checkout.getId());
    }
}
