package com.centauro.billing.domain.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Amounts {

    public static final String CURRENCY_ARS = "ARS";

    private Amounts() {
    }

    public static BigDecimal scale(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static String format(BigDecimal amount) {
        return amount == null ? null : scale(amount).toPlainString();
    }
}
