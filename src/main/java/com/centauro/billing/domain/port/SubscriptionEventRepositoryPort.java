package com.centauro.billing.domain.port;

import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.model.SubscriptionEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionEventRepositoryPort {

    /** Guarda el evento; si ya existe uno con la misma {@code dedupeKey} no hace nada y devuelve false. */
    boolean append(SubscriptionEvent event);

    List<SubscriptionEvent> findRecentBySubscriptionId(UUID subscriptionId, int limit);

    Optional<SubscriptionEvent> findByDedupeKey(UUID subscriptionId, String dedupeKey);

    /** El evento más reciente de ese tipo ({@code occurred_at} descendente). */
    Optional<SubscriptionEvent> findLatestByType(UUID subscriptionId, SubscriptionEventType type);
}
