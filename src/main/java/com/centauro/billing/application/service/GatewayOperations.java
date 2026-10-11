package com.centauro.billing.application.service;

import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sincroniza con la pasarela solo cuando la suscripción tiene un {@code gatewaySubscriptionId}.
 * Sin pasarela configurada y sin id, no llama a nada.
 *
 * <p>La disponibilidad de la pasarela se valida en el acto (falla con 502 y la transacción hace rollback),
 * pero la llamada remota se ejecuta después del commit: si la operación local falla, la pasarela no se
 * entera. Un error remoto después del commit no deshace el cambio local; se loguea para conciliar.
 * Sin transacción activa la llamada es directa y el error se propaga.
 */
@Component
public class GatewayOperations {

    private static final Logger log = LoggerFactory.getLogger(GatewayOperations.class);

    private final Optional<PaymentGateway> gateway;

    @Autowired
    public GatewayOperations(ObjectProvider<PaymentGateway> gateway) {
        this(Optional.ofNullable(gateway.getIfAvailable()));
        if (this.gateway.isEmpty()) {
            log.info("Pasarela de pago no configurada (sin MP_ACCESS_TOKEN): checkout y conciliación deshabilitados");
        }
    }

    public GatewayOperations(Optional<PaymentGateway> gateway) {
        this.gateway = gateway;
    }

    public boolean isAvailable() {
        return gateway.isPresent();
    }

    public Optional<PaymentGateway> gateway() {
        return gateway;
    }

    /** La pasarela o 502 {@code GATEWAY_UNAVAILABLE}. */
    public PaymentGateway requireGateway() {
        return gateway.orElseThrow(() -> new GatewayUnavailableException("La pasarela de pago no está configurada"));
    }

    /**
     * Cancela un preapproval que quedó sin uso (checkout reemplazado o vencido, débito reemplazado por otro)
     * después del commit, o en el acto sin transacción. Best effort: un error se loguea y lo retoma la
     * conciliación.
     */
    public void cancelQuietlyAfterCommit(String gatewaySubscriptionId, String context) {
        if (gatewaySubscriptionId == null || gateway.isEmpty()) {
            return;
        }
        Runnable call = () -> cancelQuietly(gatewaySubscriptionId, context);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            call.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                call.run();
            }
        });
    }

    public void cancelQuietly(String gatewaySubscriptionId, String context) {
        if (gatewaySubscriptionId == null || gateway.isEmpty()) {
            return;
        }
        try {
            gateway.get().cancel(gatewaySubscriptionId);
        } catch (RuntimeException e) {
            log.warn("Pasarela: no se pudo cancelar el preapproval {} ({}): {}; lo retoma la conciliación",
                    gatewaySubscriptionId, context, e.getMessage());
        }
    }

    /**
     * Pausa el débito vigente después del commit (suspensión administrativa). Best effort y sin exigir pasarela:
     * la suspensión no puede fallar por la pasarela; si la llamada falla, la conciliación vuelve a pausar.
     */
    public void pauseQuietlyAfterCommit(Subscription subscription) {
        quietlyAfterCommit(subscription, "pause", PaymentGateway::pause);
    }

    /** Reanuda el débito vigente después del commit (reactivación administrativa). Mismo criterio que la pausa. */
    public void resumeQuietlyAfterCommit(Subscription subscription) {
        quietlyAfterCommit(subscription, "resume", PaymentGateway::resume);
    }

    private void quietlyAfterCommit(Subscription subscription, String operation,
                                    BiConsumer<PaymentGateway, String> call) {
        String preapprovalId = subscription.getGatewaySubscriptionId();
        if (!subscription.hasGatewaySubscription() || gateway.isEmpty()
                || GatewaySubscriptionStatus.CANCELLED.storedValue().equals(subscription.getGatewayStatus())) {
            return;
        }
        PaymentGateway target = gateway.get();
        Runnable run = () -> {
            try {
                call.accept(target, preapprovalId);
            } catch (RuntimeException e) {
                log.warn("Pasarela: falló {} del preapproval {} para la suscripción {}: {}; lo retoma la conciliación",
                        operation, preapprovalId, subscription.getId(), e.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            run.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run.run();
            }
        });
    }

    /** Falla antes de mutar nada si la suscripción depende de una pasarela que no está configurada. */
    public void requireAvailable(Subscription subscription) {
        if (subscription.hasGatewaySubscription()) {
            requireLinkedGateway();
        }
    }

    public void cancelAfterCommit(Subscription subscription) {
        afterCommit(subscription, "cancel", g -> g.cancel(subscription.getGatewaySubscriptionId()));
    }

    public void updateAmountAfterCommit(Subscription subscription, BigDecimal amount) {
        afterCommit(subscription, "updateAmount",
                g -> g.updateAmount(subscription.getGatewaySubscriptionId(), amount));
    }

    private void afterCommit(Subscription subscription, String operation, Consumer<PaymentGateway> call) {
        if (!subscription.hasGatewaySubscription()) {
            return;
        }
        PaymentGateway target = requireLinkedGateway();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            call.accept(target);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    call.accept(target);
                } catch (RuntimeException e) {
                    log.error("Pasarela: falló {} después del commit para la suscripción {}; requiere conciliación",
                            operation, subscription.getId(), e);
                }
            }
        });
    }

    private PaymentGateway requireLinkedGateway() {
        return gateway.orElseThrow(() -> new GatewayUnavailableException(
                "La suscripción está vinculada a una pasarela que no está configurada"));
    }
}
