package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.exception.InvalidBillingRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Amounts {

    public static final String CURRENCY_ARS = "ARS";
    public static final int MAX_INTEGER_DIGITS = 10;
    public static final int SCALE = 2;

    private Amounts() {
    }

    /** Importe recibido de un cliente: mayor a cero y sin más de 2 decimales significativos (no se redondea). */
    public static BigDecimal requirePositiveMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidBillingRequestException("El importe debe ser mayor a cero");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > SCALE) {
            throw new InvalidBillingRequestException("El importe admite como máximo " + SCALE + " decimales");
        }
        if (normalized.precision() - normalized.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidBillingRequestException("El importe excede el máximo admitido");
        }
        return scale(amount);
    }

    public static BigDecimal scale(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static String format(BigDecimal amount) {
        return amount == null ? null : scale(amount).toPlainString();
    }
}
