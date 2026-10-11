package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public record SubscriptionEventView(
        String type,
        String fromStatus,
        String toStatus,
        String actorType,
        String actor,
        String reason,
        Map<String, Object> data,
        OffsetDateTime occurredAt
) {
}