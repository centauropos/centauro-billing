package com.centauro.billing.support;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.BillingTerms;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TestFixtures {

    public static final ZoneId AR = ZoneId.of("America/Argentina/Buenos_Aires");
    public static final ZoneOffset AR_OFFSET = ZoneOffset.ofHours(-3);
    /** 2026-10-11 10:00 -03:00. */
    public static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 11, 10, 0, 0, 0, AR_OFFSET);
    public static final BillingTerms TERMS = new BillingTerms(14, 3, 7, "PROFESIONAL", AR);

    private TestFixtures() {
    }

    public static Clock clockAt(OffsetDateTime instant) {
        return Clock.fixed(instant.toInstant(), ZoneOffset.UTC);
    }

    public static Plan plan(String code, boolean publicVisible, BigDecimal amount) {
        UUID id = UUID.randomUUID();
        List<PlanPrice> prices = amount == null ? List.of()
                : List.of(new PlanPrice(UUID.randomUUID(), id, "ARS", null, amount,
                OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, AR_OFFSET), "seed"));
        return new Plan(id, code, code, null, true, publicVisible, false, 10, Set.of("SALES_CASH"),
                Map.of("maxArcaInvoicesPerMonth", 100), prices);
    }

    /** Plan a medida (EMPRESA): sin precios versionados. */
    public static Plan customPlan(String code, boolean publicVisible) {
        return new Plan(UUID.randomUUID(), code, code, null, true, publicVisible, true, 40,
                Set.of("SALES_CASH", "METRICS_FINANCIAL"), Map.of(), List.of());
    }

    public static Builder subscription() {
        return new Builder();
    }

    public static final class Builder {
        private String tenantId = "tenant-1";
        private UUID planId = UUID.randomUUID();
        private SubscriptionStatus status = SubscriptionStatus.TRIALING;
        private SuspensionReason suspensionReason;
        private PastDueReason pastDueReason;
        private OffsetDateTime trialEndsAt = NOW.plusDays(14);
        private OffsetDateTime currentPeriodStart;
        private OffsetDateTime currentPeriodEnd;
        private Integer billingAnchorDay;
        private OffsetDateTime graceEndsAt;
        private boolean cancelAtPeriodEnd;
        private BigDecimal priceAmount = new BigDecimal("25000.00");
        private String gatewaySubscriptionId;
        private GatewayName gateway;

        public Builder tenantId(String value) { tenantId = value; return this; }
        public Builder planId(UUID value) { planId = value; return this; }
        public Builder status(SubscriptionStatus value) { status = value; return this; }
        public Builder suspensionReason(SuspensionReason value) { suspensionReason = value; return this; }
        public Builder pastDueReason(PastDueReason value) { pastDueReason = value; return this; }
        public Builder trialEndsAt(OffsetDateTime value) { trialEndsAt = value; return this; }
        public Builder currentPeriodStart(OffsetDateTime value) { currentPeriodStart = value; return this; }
        public Builder currentPeriodEnd(OffsetDateTime value) { currentPeriodEnd = value; return this; }
        public Builder billingAnchorDay(Integer value) { billingAnchorDay = value; return this; }
        public Builder graceEndsAt(OffsetDateTime value) { graceEndsAt = value; return this; }
        public Builder cancelAtPeriodEnd(boolean value) { cancelAtPeriodEnd = value; return this; }
        public Builder priceAmount(BigDecimal value) { priceAmount = value; return this; }
        public Builder gatewaySubscriptionId(String value) {
            gatewaySubscriptionId = value;
            gateway = value == null ? null : GatewayName.MERCADOPAGO;
            return this;
        }

        public Subscription build() {
            return new Subscription(UUID.randomUUID(), tenantId, planId, status, suspensionReason, pastDueReason,
                    trialEndsAt, currentPeriodStart, currentPeriodEnd, billingAnchorDay, graceEndsAt,
                    cancelAtPeriodEnd, null, null,
                    priceAmount, "ARS", gateway, gatewaySubscriptionId, null, null, null, "owner@test.com",
                    "Empresa Test", 0L, NOW.minusDays(30));
        }
    }
}