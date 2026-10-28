package com.centauro.billing.domain.port;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pasarela de cobro recurrente. Las implementaciones traducen sus errores a {@code GatewayRejectedException}
 * (la pasarela rechazó el pedido), {@code GatewayUnavailableException} (error del servidor o de red) y
 * {@code GatewayTimeoutException}.
 */
public interface PaymentGateway {

    GatewayName name();

    /**
     * Crea el preapproval without-plan-authorized ({@code status=authorized} + {@code card_token_id}).
     * Idempotente por {@code checkoutId}. {@code initPoint} del resultado puede ser null.
     */
    RecurringCheckoutResult createRecurringCheckout(RecurringCheckoutRequest request);

    GatewaySubscriptionSnapshot getSubscription(String gatewaySubscriptionId);

    GatewayChargeSnapshot getCharge(String gatewayChargeId);

    List<GatewayChargeSnapshot> listCharges(String gatewaySubscriptionId);

    void updateAmount(String gatewaySubscriptionId, BigDecimal amount);

    /** Sin idempotencia del lado de la pasarela: cancelar algo ya cancelado puede fallar. */
    void cancel(String gatewaySubscriptionId);

    /** Suspende los cobros sin cancelar la adhesión (suspensión administrativa). */
    void pause(String gatewaySubscriptionId);

    /** Reanuda una adhesión pausada. */
    void resume(String gatewaySubscriptionId);
}
