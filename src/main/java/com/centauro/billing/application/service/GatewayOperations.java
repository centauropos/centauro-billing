package com.centauro.billing.application.service;

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
    }

    public GatewayOperations(Optional<PaymentGateway> gateway) {
        this.gateway = gateway;
    }

    /** Falla antes de mutar nada si la suscripción depende de una pasarela que no está configurada. */
    public void requireAvailable(Subscription subscription) {
        if (subscription.hasGatewaySubscription()) {
            requireGateway();
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
        PaymentGateway target = requireGateway();
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

    private PaymentGateway requireGateway() {
        return gateway.orElseThrow(() -> new GatewayUnavailableException(
                "La suscripción está vinculada a una pasarela que no está configurada"));
    }
}
