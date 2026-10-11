package com.centauro.billing.domain.policy;

import java.time.ZoneId;
import java.util.Objects;

/** Parámetros de negocio que usan las políticas y los casos de uso. */
public record BillingTerms(int trialDays, int trialGraceDays, int graceDays, String trialPlanCode, ZoneId zone) {

    public BillingTerms {
        Objects.requireNonNull(zone, "zone");
        if (trialDays < 1 || trialGraceDays < 0 || graceDays < 0) {
            throw new IllegalArgumentException("Días de trial o de gracia inválidos");
        }
        if (trialPlanCode == null || trialPlanCode.isBlank()) {
            throw new IllegalArgumentException("billing.trial.plan-code es obligatorio");
        }
    }
}
