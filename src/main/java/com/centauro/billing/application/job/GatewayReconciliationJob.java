package com.centauro.billing.application.job;

import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.GatewaySettings;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Conciliación con la pasarela (lock {@code 1004}). Cubre webhooks perdidos y las llamadas posteriores al commit
 * que fallaron ({@code GatewayOperations}):
 * <ul>
 *   <li>por cada suscripción vinculada: reconsulta el preapproval y sus cobros y los aplica igual que el webhook;
 *       después corrige la deriva que informe {@link GatewaySyncService#detectDrift} (cancelar, pausar, reanudar
 *       o actualizar el importe);</li>
 *   <li>checkouts que ya no son el vigente, tocados en los últimos 30 días, cuyo preapproval quedó vivo: si el
 *       checkout está vencido o fallido pasa por el flujo normal (que lo vincula o lo descarta); si no, se
 *       cancela. Nunca se cancela el preapproval vigente de la suscripción.</li>
 * </ul>
 * Cada corrida se corta al llegar a {@code reconciliationMaxItems} elementos o a {@code reconciliationMaxDuration};
 * lo que queda lo toma la corrida siguiente. Sin pasarela configurada no corre.
 */
@Component
@ConditionalOnProperty(name = "billing.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class GatewayReconciliationJob {

    public static final long LOCK_KEY = 1004L;
    static final int BATCH_SIZE = 50;
    static final Duration STALE_CHECKOUT_WINDOW = Duration.ofDays(30);
    static final Actor JOB_ACTOR = Actor.job("GatewayReconciliationJob");

    private static final Logger log = LoggerFactory.getLogger(GatewayReconciliationJob.class);

    private final SubscriptionRepositoryPort subscriptions;
    private final CheckoutRepositoryPort checkouts;
    private final GatewaySyncService sync;
    private final GatewayOperations gatewayOperations;
    private final GatewaySettings settings;
    private final JobLockPort jobLock;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public GatewayReconciliationJob(SubscriptionRepositoryPort subscriptions, CheckoutRepositoryPort checkouts,
                                    GatewaySyncService sync, GatewayOperations gatewayOperations,
                                    GatewaySettings settings, JobLockPort jobLock, Clock clock) {
        this(subscriptions, checkouts, sync, gatewayOperations, settings, jobLock, clock, BATCH_SIZE);
    }

    GatewayReconciliationJob(SubscriptionRepositoryPort subscriptions, CheckoutRepositoryPort checkouts,
                             GatewaySyncService sync, GatewayOperations gatewayOperations, GatewaySettings settings,
                             JobLockPort jobLock, Clock clock, int batchSize) {
        this.subscriptions = subscriptions;
        this.checkouts = checkouts;
        this.sync = sync;
        this.gatewayOperations = gatewayOperations;
        this.settings = settings;
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
            Budget budget = new Budget(settings.reconciliationMaxItems(),
                    clock.instant().plus(settings.reconciliationMaxDuration()));
            int corrected = sweep(budget, cursor -> subscriptions.findWithGatewaySubscription(cursor, batchSize),
                    id -> reconcileSubscription(gateway, id, now), "la suscripción");
            int canceled = sweep(budget, cursor -> checkouts.findStaleWithPreapproval(now.minus(STALE_CHECKOUT_WINDOW),
                    cursor, batchSize), id -> reconcileCheckout(gateway, id, now), "el checkout");
            log.info("GatewayReconciliationJob: correcciones={} preapprovals huérfanos resueltos={} procesados={}{}",
                    corrected, canceled, budget.used, budget.exhausted ? " (corte por límite)" : "");
        });
        if (!acquired) {
            log.info("GatewayReconciliationJob: lock {} tomado por otra instancia, se omite", LOCK_KEY);
        }
        return acquired;
    }

    private final class Budget {
        private final int maxItems;
        private final Instant deadline;
        private int used;
        private boolean exhausted;

        private Budget(int maxItems, Instant deadline) {
            this.maxItems = maxItems;
            this.deadline = deadline;
        }

        boolean take() {
            if (exhausted || used >= maxItems || clock.instant().isAfter(deadline)) {
                exhausted = true;
                return false;
            }
            used++;
            return true;
        }
    }

    private int sweep(Budget budget, Function<UUID, List<UUID>> page, ToIntFunction<UUID> handler, String kind) {
        int total = 0;
        UUID cursor = SubscriptionLifecycleJob.CURSOR_START;
        while (true) {
            List<UUID> ids = page.apply(cursor);
            for (UUID id : ids) {
                if (!budget.take()) {
                    return total;
                }
                try {
                    total += handler.applyAsInt(id);
                } catch (RuntimeException e) {
                    log.error("GatewayReconciliationJob: falló {} {}: {}", kind, id, e.getMessage());
                }
            }
            if (ids.size() < batchSize) {
                return total;
            }
            cursor = ids.get(ids.size() - 1);
        }
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
            case CANCEL -> gateway.cancel(preapprovalId);
            case PAUSE -> gateway.pause(preapprovalId);
            case RESUME -> gateway.resume(preapprovalId);
            case UPDATE_AMOUNT -> gateway.updateAmount(preapprovalId, drift.amount());
            default -> {
                return 0;
            }
        }
        log.info("GatewayReconciliationJob: preapproval {} corregido ({})", preapprovalId, drift.action());
        return 1;
    }

    int reconcileCheckout(PaymentGateway gateway, UUID checkoutId, OffsetDateTime now) {
        Optional<GatewayCheckout> found = checkouts.findById(checkoutId);
        if (found.isEmpty() || found.get().getGatewayPreapprovalId() == null) {
            return 0;
        }
        String preapprovalId = found.get().getGatewayPreapprovalId();
        if (isCurrent(found.get().getSubscriptionId(), preapprovalId)) {
            return 0;
        }
        GatewaySubscriptionSnapshot snapshot = gateway.getSubscription(preapprovalId);
        if (snapshot.status() != GatewaySubscriptionStatus.AUTHORIZED
                && snapshot.status() != GatewaySubscriptionStatus.PAUSED) {
            return 0;
        }
        CheckoutStatus status = found.get().getStatus();
        if (snapshot.status() == GatewaySubscriptionStatus.AUTHORIZED
                && (status == CheckoutStatus.EXPIRED || status == CheckoutStatus.FAILED)) {
            // Autorizado tarde con el webhook perdido: el flujo normal decide si se vincula o se descarta.
            GatewaySyncService.Outcome outcome = sync.applyPreapproval(snapshot, JOB_ACTOR, now);
            log.info("GatewayReconciliationJob: checkout {} {} con preapproval {} vivo: {}", status, checkoutId,
                    preapprovalId, outcome.detail());
            return outcome.processed() ? 1 : 0;
        }
        // Se vuelve a mirar justo antes de cancelar: un webhook pudo haberlo vinculado mientras tanto.
        if (isCurrent(found.get().getSubscriptionId(), preapprovalId)) {
            return 0;
        }
        gateway.cancel(preapprovalId);
        log.info("GatewayReconciliationJob: preapproval huérfano {} del checkout {} cancelado", preapprovalId,
                checkoutId);
        return 1;
    }

    private boolean isCurrent(UUID subscriptionId, String preapprovalId) {
        return subscriptions.findById(subscriptionId)
                .map(s -> Objects.equals(preapprovalId, s.getGatewaySubscriptionId()))
                .orElse(false);
    }
}
