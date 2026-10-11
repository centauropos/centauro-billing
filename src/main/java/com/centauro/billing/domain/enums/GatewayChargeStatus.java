package com.centauro.billing.domain.enums;

/** Estado normalizado de un cobro recurrente (authorized_payment en Mercado Pago). */
public enum GatewayChargeStatus {
    APPROVED, REJECTED, PENDING, CANCELLED, UNKNOWN
}
