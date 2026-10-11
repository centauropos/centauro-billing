package com.centauro.billing.support;

import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.model.WebhookEvent;
import com.centauro.billing.domain.port.WebhookEventRepositoryPort;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryWebhookEventRepository implements WebhookEventRepositoryPort {

    private final Map<UUID, WebhookEvent> byId = new LinkedHashMap<>();
    public int updates;

    public List<WebhookEvent> all() {
        return List.copyOf(byId.values());
    }

    public void put(WebhookEvent event) {
        byId.put(event.getId(), event);
    }

    @Override
    public boolean insertIfAbsent(WebhookEvent event) {
        boolean duplicate = byId.values().stream().anyMatch(e -> e.getProvider() == event.getProvider()
                && e.getDedupeKey().equals(event.getDedupeKey()));
        if (duplicate) {
            return false;
        }
        byId.put(event.getId(), event);
        return true;
    }

    @Override
    public void update(WebhookEvent event) {
        updates++;
        byId.put(event.getId(), event);
    }

    @Override
    public Optional<WebhookEvent> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<UUID> findRetryable(OffsetDateTime now, int maxAttempts, UUID afterId, int limit) {
        return byId.values().stream()
                .filter(e -> e.getStatus() == WebhookStatus.RECEIVED || e.getStatus() == WebhookStatus.FAILED)
                .filter(e -> e.getNextAttemptAt() != null && !e.getNextAttemptAt().isAfter(now))
                .filter(e -> e.getAttempts() < maxAttempts)
                .map(WebhookEvent::getId)
                .filter(id -> id.toString().compareTo(afterId.toString()) > 0)
                .sorted(Comparator.comparing(UUID::toString))
                .limit(limit).toList();
    }
}
