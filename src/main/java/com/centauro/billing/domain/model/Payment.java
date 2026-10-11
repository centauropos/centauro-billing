package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.PaymentSource;
import com.centauro.billing.domain.enums.PaymentStatus;
import com.centauro.billing.domain.vo.Amounts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Payment(
        UUID id,
        UUID subscriptionId,
        String tenantId,
        PaymentSource source,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String gatewayPaymentId,
        String gatewayChargeId,
        String gatewayStatus,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        OffsetDateTime paidAt,
        boolean applied,
        String reference,
        String notes,
        String registeredBy,
        OffsetDateTime createdAt
) {

    public Payment {
        amount = Amounts.scale(amount);
        currency = currency == null ? Amounts.CURRENCY_ARS : currency;
    }

    public static Payment manual(Subscription subscription, BigDecimal amount, OffsetDateTime paidAt,
                                 OffsetDateTime periodStart, OffsetDateTime periodEnd, String reference,
                                 String notes, String registeredBy, OffsetDateTime now) {
        return new Payment(UUID.randomUUID(), subscription.getId(), subscription.getTenantId(),
                PaymentSource.MANUAL, PaymentStatus.APPROVED, amount, Amounts.CURRENCY_ARS, null, null, null,
                periodStart, periodEnd, paidAt, false, reference, notes, registeredBy, now);
    }

    /** Cobro de la pasarela. {@code gatewayPaymentId} es la clave de idempotencia (el cobro recurrente). */
    public static Payment fromGateway(Subscription subscription, PaymentSource source, PaymentStatus status,
                                      BigDecimal amount, String currency, String gatewayPaymentId,
                                      String gatewayChargeId, String gatewayStatus, OffsetDateTime paidAt,
                                      String registeredBy, OffsetDateTime now) {
        return new Payment(UUID.randomUUID(), subscription.getId(), subscription.getTenantId(), source, status,
                amount == null ? BigDecimal.ZERO : amount, currency, gatewayPaymentId, gatewayChargeId,
                gatewayStatus, null, null, paidAt, false, null, null, registeredBy, now);
    }

    public Payment withGatewayState(PaymentStatus newStatus, BigDecimal newAmount, String newCurrency,
                                    String newChargeId, String newGatewayStatus, OffsetDateTime newPaidAt) {
        return new Payment(id, subscriptionId, tenantId, source, newStatus,
                newAmount == null ? amount : newAmount, newCurrency == null ? currency : newCurrency,
                gatewayPaymentId, newChargeId == null ? gatewayChargeId : newChargeId, newGatewayStatus,
                periodStart, periodEnd, newPaidAt == null ? paidAt : newPaidAt, applied, reference, notes,
                registeredBy, createdAt);
    }

    public Payment withPeriod(OffsetDateTime start, OffsetDateTime end) {
        return new Payment(id, subscriptionId, tenantId, source, status, amount, currency, gatewayPaymentId,
                gatewayChargeId, gatewayStatus, start, end, paidAt, applied, reference, notes, registeredBy,
                createdAt);
    }

    public Payment markApplied() {
        return new Payment(id, subscriptionId, tenantId, source, status, amount, currency, gatewayPaymentId,
                gatewayChargeId, gatewayStatus, periodStart, periodEnd, paidAt, true, reference, notes,
                registeredBy, createdAt);
    }
}
