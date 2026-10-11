package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.BillingPeriod;
import com.centauro.billing.domain.vo.Amounts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record PlanPrice(
        UUID id,
        UUID planId,
        String currency,
        BillingPeriod period,
        BigDecimal amount,
        OffsetDateTime validFrom,
        String createdBy
) {

    public PlanPrice {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(validFrom, "validFrom");
        currency = currency == null ? Amounts.CURRENCY_ARS : currency;
        period = period == null ? BillingPeriod.MONTHLY : period;
        amount = Amounts.scale(amount);
    }

    public static PlanPrice create(UUID planId, BigDecimal amount, OffsetDateTime validFrom, String createdBy) {
        return new PlanPrice(UUID.randomUUID(), planId, Amounts.CURRENCY_ARS, BillingPeriod.MONTHLY,
                amount, validFrom, createdBy);
    }

    public boolean isEffectiveAt(OffsetDateTime now) {
        return !validFrom.isAfter(now);
    }
}
