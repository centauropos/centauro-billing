package com.centauro.billing.infrastructure.adapter;

import com.centauro.billing.domain.model.SubscriptionEvent;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;
import com.centauro.billing.infrastructure.jpa.entity.SubscriptionEventEntity;
import com.centauro.billing.infrastructure.jpa.repository.SubscriptionEventJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Component
public class SubscriptionEventRepositoryAdapter implements SubscriptionEventRepositoryPort {

    private final SubscriptionEventJpaRepository repository;

    public SubscriptionEventRepositoryAdapter(SubscriptionEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean append(SubscriptionEvent event) {
        if (event.dedupeKey() != null
                && repository.existsBySubscriptionIdAndDedupeKey(event.subscriptionId(), event.dedupeKey())) {
            return false;
        }
        SubscriptionEventEntity entity = new SubscriptionEventEntity();
        entity.setId(event.id());
        entity.setSubscriptionId(event.subscriptionId());
        entity.setTenantId(event.tenantId());
        entity.setType(event.type());
        entity.setFromStatus(event.fromStatus());
        entity.setToStatus(event.toStatus());
        entity.setActorType(event.actorType());
        entity.setActor(event.actor());
        entity.setReason(event.reason());
        entity.setData(event.data().isEmpty() ? null : new LinkedHashMap<>(event.data()));
        entity.setDedupeKey(event.dedupeKey());
        entity.setOccurredAt(event.occurredAt());
        repository.save(entity);
        return true;
    }

    @Override
    public List<SubscriptionEvent> findRecentBySubscriptionId(UUID subscriptionId, int limit) {
        return repository.findBySubscriptionIdOrderByOccurredAtDesc(subscriptionId, PageRequest.of(0, limit))
                .stream()
                .map(e -> new SubscriptionEvent(e.getId(), e.getSubscriptionId(), e.getTenantId(), e.getType(),
                        e.getFromStatus(), e.getToStatus(), e.getActorType(), e.getActor(), e.getReason(),
                        e.getData(), e.getDedupeKey(), e.getOccurredAt()))
                .toList();
    }
}
