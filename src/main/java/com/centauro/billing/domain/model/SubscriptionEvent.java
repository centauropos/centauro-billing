package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.ActorType;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.vo.Actor;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record SubscriptionEvent(
        UUID id,
        UUID subscriptionId,
        String tenantId,
        SubscriptionEventType type,
        SubscriptionStatus fromStatus,
        SubscriptionStatus toStatus,
        ActorType actorType,
        String actor,
        String reason,
        Map<String, Object> data,
        String dedupeKey,
        OffsetDateTime occurredAt
) {

    public SubscriptionEvent {
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public static SubscriptionEvent of(Subscription subscription, SubscriptionEventType type,
                                       SubscriptionStatus from, Actor actor, String reason,
                                       Map<String, Object> data, String dedupeKey, OffsetDateTime now) {
        return new SubscriptionEvent(UUID.randomUUID(), subscription.getId(), subscription.getTenantId(), type,
                from, subscription.getStatus(), actor.type(), actor.name(), reason, data, dedupeKey, now);
    }
}
