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

    public Payment markApplied() {
        return new Payment(id, subscriptionId, tenantId, source, status, amount, currency, gatewayPaymentId,
                gatewayChargeId, gatewayStatus, periodStart, periodEnd, paidAt, true, reference, notes,
                registeredBy, createdAt);
    }
}
