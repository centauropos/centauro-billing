package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

public record AccessView(
        String tenantId,
        boolean known,
        String status,
        String effectiveStatus,
        String accessLevel,
        String planCode,
        Set<String> features,
        Map<String, Integer> limits,
        OffsetDateTime trialEndsAt,
        OffsetDateTime currentPeriodEnd,
        OffsetDateTime graceEndsAt,
        boolean cancelAtPeriodEnd,
        NoticeView notice,
        OffsetDateTime evaluatedAt,
        int cacheTtlSeconds
) {
}