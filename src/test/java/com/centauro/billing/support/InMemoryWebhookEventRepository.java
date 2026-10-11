package com.centauro.billing.support;

import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.model.WebhookEvent;
import com.centauro.billing.domain.port.WebhookEventRepositoryPort;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Guarda la misma instancia, pero recuerda el estado persistido para simular el UPDATE condicional. */
public class InMemoryWebhookEventRepository implements WebhookEventRepositoryPort {

    private record Stored(WebhookStatus status, int attempts) {
    }

    private final Map<UUID, WebhookEvent> byId = new LinkedHashMap<>();
    private final Map<UUID, Stored> persisted = new HashMap<>();
    public int updates;
    public int rejectedUpdates;

    public List<WebhookEvent> all() {
        return List.copyOf(byId.values());
    }

    public void put(WebhookEvent event) {
        byId.put(event.getId(), event);
        persisted.put(event.getId(), new Stored(event.getStatus(), event.getAttempts()));
    }

    /** Simula que otro proceso actualizó la fila. */
    public void overwritePersisted(UUID id, WebhookStatus status, int attempts) {
        persisted.put(id, new Stored(status, attempts));
    }

    @Override
    public boolean insertIfAbsent(WebhookEvent event) {
        boolean duplicate = byId.values().stream().anyMatch(e -> e.getProvider() == event.getProvider()
                && e.getDedupeKey().equals(event.getDedupeKey()));
        if (duplicate) {
            return false;
        }
        put(event);
        return true;
    }

    @Override
    public boolean update(WebhookEvent event, WebhookStatus expectedStatus, int expectedAttempts) {
        Stored stored = persisted.get(event.getId());
        if (stored == null || stored.status() != expectedStatus || stored.attempts() != expectedAttempts) {
            rejectedUpdates++;
            return false;
        }
        updates++;
        put(event);
        return true;
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
