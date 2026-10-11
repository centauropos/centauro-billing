package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.enums.GatewayChargeStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Un cobro recurrente reconsultado en la pasarela. {@code chargeId} identifica el cobro (en Mercado Pago, el
 * authorized_payment) y es la clave de idempotencia: se guarda en {@code payments.gateway_payment_id}.
 * {@code transactionId} es el pago concreto del intento (en MP, {@code payment.id}) y va a
 * {@code payments.gateway_charge_id}.
 */
public record GatewayChargeSnapshot(
        String chargeId,
        String gatewaySubscriptionId,
        String transactionId,
        GatewayChargeStatus status,
        String rawStatus,
        BigDecimal amount,
        String currency,
        OffsetDateTime chargedAt,
        OffsetDateTime lastModified
) {
}
