package com.centauro.billing.support;

import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.model.SubscriptionEvent;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class InMemoryEventRepository implements SubscriptionEventRepositoryPort {

    public final List<SubscriptionEvent> events = new ArrayList<>();

    @Override
    public boolean append(SubscriptionEvent event) {
        if (event.dedupeKey() != null && events.stream().anyMatch(e ->
                e.subscriptionId().equals(event.subscriptionId()) && event.dedupeKey().equals(e.dedupeKey()))) {
            return false;
        }
        events.add(event);
        return true;
    }

    @Override
    public List<SubscriptionEvent> findRecentBySubscriptionId(UUID subscriptionId, int limit) {
        return events.stream().filter(e -> e.subscriptionId().equals(subscriptionId)).limit(limit).toList();
    }

    @Override
    public Optional<SubscriptionEvent> findByDedupeKey(UUID subscriptionId, String dedupeKey) {
        return events.stream().filter(e -> e.subscriptionId().equals(subscriptionId)
                && dedupeKey.equals(e.dedupeKey())).findFirst();
    }

    @Override
    public Optional<SubscriptionEvent> findLatestByType(UUID subscriptionId, SubscriptionEventType type) {
        return events.stream().filter(e -> e.subscriptionId().equals(subscriptionId) && e.type() == type)
                .max(Comparator.comparing(SubscriptionEvent::occurredAt));
    }

    public List<SubscriptionEventType> types() {
        return events.stream().map(SubscriptionEvent::type).toList();
    }
}