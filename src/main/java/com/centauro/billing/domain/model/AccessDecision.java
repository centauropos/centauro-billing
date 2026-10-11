package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.AccessLevel;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;

import java.time.OffsetDateTime;

/**
 * Resultado de evaluar una suscripción contra el reloj. {@code effectiveGraceEndsAt} es la gracia
 * que corresponde al estado efectivo, aunque el job todavía no la haya persistido.
 */
public record AccessDecision(
        boolean known,
        SubscriptionStatus status,
        SubscriptionStatus effectiveStatus,
        SuspensionReason effectiveSuspensionReason,
        AccessLevel accessLevel,
        OffsetDateTime effectiveGraceEndsAt,
        OffsetDateTime evaluatedAt
) {

    public static AccessDecision unknown(OffsetDateTime now) {
        return new AccessDecision(false, null, null, null, AccessLevel.FULL, null, now);
    }
}
