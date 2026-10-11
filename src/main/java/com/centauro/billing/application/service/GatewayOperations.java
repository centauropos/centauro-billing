package com.centauro.billing.application.service;

import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.PaymentGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Sincroniza con la pasarela solo cuando la suscripción tiene un {@code gatewaySubscriptionId}.
 * Sin pasarela configurada y sin id, no llama a nada.
 */
@Component
public class GatewayOperations {

    private final Optional<PaymentGateway> gateway;

    @Autowired
    public GatewayOperations(ObjectProvider<PaymentGateway> gateway) {
        this(Optional.ofNullable(gateway.getIfAvailable()));
    }

    public GatewayOperations(Optional<PaymentGateway> gateway) {
        this.gateway = gateway;
    }

    public void cancel(Subscription subscription) {
        if (subscription.hasGatewaySubscription()) {
            requireGateway().cancel(subscription.getGatewaySubscriptionId());
        }
    }

    public void updateAmount(Subscription subscription, BigDecimal amount) {
        if (subscription.hasGatewaySubscription()) {
            requireGateway().updateAmount(subscription.getGatewaySubscriptionId(), amount);
        }
    }

    private PaymentGateway requireGateway() {
        return gateway.orElseThrow(() -> new GatewayUnavailableException(
                "La suscripción está vinculada a una pasarela que no está configurada"));
    }
}
