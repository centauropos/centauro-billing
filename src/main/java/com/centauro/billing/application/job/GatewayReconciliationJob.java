package com.centauro.billing.application.job;

import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.GatewaySyncService;
import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import com.centauro.billing.domain.port.JobLockPort;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Conciliación con la pasarela (lock {@code 1004}). Cubre webhooks perdidos y las llamadas posteriores al commit
 * que fallaron ({@code GatewayOperations}):
 * <ul>
 *   <li>por cada suscripción vinculada: reconsulta el preapproval y sus cobros y los aplica igual que el webhook;
 *       si la baja es local pero el débito sigue vivo, lo cancela; si el importe difiere del precio, lo
 *       actualiza (evento {@code GATEWAY_AMOUNT_MISMATCH});</li>
 *   <li>checkouts reemplazados o cancelados en los últimos 30 días cuyo preapproval quedó autorizado sin ser
 *       el vigente: lo cancela. Si el checkout está vencido, lo vincula como el webhook.</li>
 * </ul>
 * Sin pasarela configurada no corre.
 */
@Component
@ConditionalOnProperty(name = "billing.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class GatewayReconciliationJob {

    public static final long LOCK_KEY = 1004L;
    static final int BATCH_SIZE = 50;
    static final int MAX_BATCHES = 200;
    static final Duration STALE_CHECKOUT_WINDOW = Duration.ofDays(30);
    static final Actor JOB_ACTOR = Actor.job("GatewayReconciliationJob");

    private static final Logger log = LoggerFactory.getLogger(GatewayReconciliationJob.class);

    private final SubscriptionRepositoryPort subscriptions;
    private final CheckoutRepositoryPort checkouts;
    private final GatewaySyncService sync;
    private final GatewayOperations gatewayOperations;
    private final JobLockPort jobLock;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public GatewayReconciliationJob(SubscriptionRepositoryPort subscriptions, CheckoutRepositoryPort checkouts,
                                    GatewaySyncService sync, GatewayOperations gatewayOperations, JobLockPort jobLock,
                                    Clock clock) {
        this(subscriptions, checkouts, sync, gatewayOperations, jobLock, clock, BATCH_SIZE);
    }

    GatewayReconciliationJob(SubscriptionRepositoryPort subscriptions, CheckoutRepositoryPort checkouts,
                             GatewaySyncService sync, GatewayOperations gatewayOperations, JobLockPort jobLock,
                             Clock clock, int batchSize) {
        this.subscriptions = subscriptions;
        this.checkouts = checkouts;
        this.sync = sync;
        this.gatewayOperations = gatewayOperations;
        this.jobLock = jobLock;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${billing.jobs.reconciliation-cron}", zone = "${billing.zone}")
    public void scheduledRun() {
        run();
    }

    /** Devuelve false si no corrió: sin pasarela o sin lock. */
    public boolean run() {
        Optional<PaymentGateway> configured = gatewayOperations.gateway();
        if (configured.isEmpty()) {
            log.debug("GatewayReconciliationJob: sin pasarela configurada, se omite");
            return false;
        }
        PaymentGateway gateway = configured.get();
        boolean acquired = jobLock.runExclusively(LOCK_KEY, () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            int corrected = 0;
            UUID cursor = SubscriptionLifecycleJob.CURSOR_START;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<UUID> ids = subscriptions.findWithGatewaySubscription(cursor, batchSize);
                for (UUID id : ids) {
                    try {
                        corrected += reconcileSubscription(gateway, id, now);
                    } catch (RuntimeException e) {
                        log.error("GatewayReconciliationJob: falló la suscripción {}: {}", id, e.getMessage());
                    }
                }
                if (ids.size() < batchSize) {
                    break;
                }
                cursor = ids.get(ids.size() - 1);
            }
            int canceled = 0;
            cursor = SubscriptionLifecycleJob.CURSOR_START;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<UUID> ids = checkouts.findStaleWithPreapproval(now.minus(STALE_CHECKOUT_WINDOW), cursor,
                        batchSize);
                for (UUID id : ids) {
                    try {
                        canceled += reconcileCheckout(gateway, id, now);
                    } catch (RuntimeException e) {
                        log.error("GatewayReconciliationJob: falló el checkout {}: {}", id, e.getMessage());
                    }
                }
                if (ids.size() < batchSize) {
                    break;
                }
                cursor = ids.get(ids.size() - 1);
            }
            log.info("GatewayReconciliationJob: correcciones={} preapprovals huérfanos cancelados={}", corrected,
                    canceled);
        });
        if (!acquired) {
            log.info("GatewayReconciliationJob: lock {} tomado por otra instancia, se omite", LOCK_KEY);
        }
        return acquired;
    }

    /** Devuelve 1 si hubo que corregir algo en la pasarela. */
    int reconcileSubscription(PaymentGateway gateway, UUID subscriptionId, OffsetDateTime now) {
        Optional<Subscription> found = subscriptions.findById(subscriptionId);
        if (found.isEmpty() || !found.get().hasGatewaySubscription()) {
            return 0;
        }
        String preapprovalId = found.get().getGatewaySubscriptionId();
        GatewaySubscriptionSnapshot preapproval = gateway.getSubscription(preapprovalId);
        sync.applyPreapproval(preapproval, JOB_ACTOR, now);
        for (GatewayChargeSnapshot charge : gateway.listCharges(preapprovalId)) {
            try {
                sync.applyCharge(charge, null, JOB_ACTOR, now);
            } catch (RuntimeException e) {
                log.error("GatewayReconciliationJob: falló el cobro {} del preapproval {}: {}", charge.chargeId(),
                        preapprovalId, e.getMessage());
            }
        }
        GatewaySyncService.Drift drift = sync.detectDrift(subscriptionId, preapproval, JOB_ACTOR, now);
        switch (drift.action()) {
            case CANCEL -> {
                gateway.cancel(preapprovalId);
                log.info("GatewayReconciliationJob: preapproval {} cancelado (baja local)", preapprovalId);
                return 1;
            }
            case UPDATE_AMOUNT -> {
                gateway.updateAmount(preapprovalId, drift.amount());
                log.info("GatewayReconciliationJob: importe del preapproval {} corregido", preapprovalId);
                return 1;
            }
            default -> {
                return 0;
            }
        }
    }

    int reconcileCheckout(PaymentGateway gateway, UUID checkoutId, OffsetDateTime now) {
        Optional<GatewayCheckout> found = checkouts.findById(checkoutId);
        if (found.isEmpty() || found.get().getGatewayPreapprovalId() == null) {
            return 0;
        }
        String preapprovalId = found.get().getGatewayPreapprovalId();
        boolean current = subscriptions.findById(found.get().getSubscriptionId())
                .map(s -> Objects.equals(preapprovalId, s.getGatewaySubscriptionId()))
                .orElse(false);
        if (current) {
            return 0;
        }
        GatewaySubscriptionSnapshot snapshot = gateway.getSubscription(preapprovalId);
        if (snapshot.status() != GatewaySubscriptionStatus.AUTHORIZED
                && snapshot.status() != GatewaySubscriptionStatus.PAUSED) {
            return 0;
        }
        if (found.get().getStatus() == CheckoutStatus.EXPIRED) {
            // Autorizado después de vencer y con el webhook perdido: se vincula igual que desde el webhook.
            GatewaySyncService.Outcome outcome = sync.applyPreapproval(snapshot, JOB_ACTOR, now);
            log.info("GatewayReconciliationJob: checkout vencido {} con preapproval {} vivo: {}", checkoutId,
                    preapprovalId, outcome.detail());
            return outcome.processed() ? 1 : 0;
        }
        gateway.cancel(preapprovalId);
        log.info("GatewayReconciliationJob: preapproval huérfano {} del checkout {} cancelado", preapprovalId,
                checkoutId);
        return 1;
    }
}
