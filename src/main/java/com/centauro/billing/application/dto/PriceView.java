package com.centauro.billing.application.dto;

import com.centauro.billing.domain.vo.Amounts;

import java.math.BigDecimal;

public record PriceView(String amount, String currency, String period) {

    public static PriceView monthly(BigDecimal amount, String currency) {
        return amount == null ? null : new PriceView(Amounts.format(amount), currency, "MONTHLY");
    }
}