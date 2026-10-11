package com.centauro.billing.infrastructure.adapter;

import com.centauro.billing.domain.model.WebhookEvent;
import com.centauro.billing.domain.port.WebhookEventRepositoryPort;
import com.centauro.billing.infrastructure.jpa.entity.WebhookEventEntity;
import com.centauro.billing.infrastructure.jpa.repository.WebhookEventJpaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Las escrituras van en una transacción propia ({@code REQUIRES_NEW}): el job de reintentos corre dentro de la
 * transacción que retiene el advisory lock, y un error acá no tiene que marcarla para rollback.
 */
@Component
public class WebhookEventRepositoryAdapter implements WebhookEventRepositoryPort {

    private final WebhookEventJpaRepository repository;
    private final ObjectMapper objectMapper;

    public WebhookEventRepositoryAdapter(WebhookEventJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertIfAbsent(WebhookEvent e) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(e.getPayload());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Payload de webhook no serializable", ex);
        }
        return repository.insertIfAbsent(e.getId(), e.getProvider().name(), cut(e.getDedupeKey(), 150),
                cut(e.getNotificationId(), 64), cut(e.getRequestId(), 128), cut(e.getTopic(), 60),
                cut(e.getAction(), 60), cut(e.getResourceId(), 64), e.getLiveMode(), payload, e.getNextAttemptAt(),
                e.getReceivedAt()) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(WebhookEvent e) {
        WebhookEventEntity entity = repository.findById(e.getId())
                .orElseThrow(() -> new IllegalStateException("Evento de webhook inexistente: " + e.getId()));
        entity.setStatus(e.getStatus());
        entity.setAttempts(e.getAttempts());
        entity.setLastError(e.getLastError());
        entity.setNextAttemptAt(e.getNextAttemptAt());
        entity.setProcessedAt(e.getProcessedAt());
        repository.save(entity);
    }

    @Override
    public Optional<WebhookEvent> findById(UUID id) {
        return repository.findById(id).map(WebhookEventRepositoryAdapter::toDomain);
    }

    @Override
    public List<UUID> findRetryable(OffsetDateTime now, int maxAttempts, UUID afterId, int limit) {
        return repository.findRetryable(now, maxAttempts, afterId, limit);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static WebhookEvent toDomain(WebhookEventEntity e) {
        return new WebhookEvent(e.getId(), e.getProvider(), e.getDedupeKey(), e.getNotificationId(),
                e.getRequestId(), e.getTopic(), e.getAction(), e.getResourceId(), e.getLiveMode(), e.getPayload(),
                e.getStatus(), e.getAttempts(), e.getLastError(), e.getNextAttemptAt(), e.getReceivedAt(),
                e.getProcessedAt());
    }
}
